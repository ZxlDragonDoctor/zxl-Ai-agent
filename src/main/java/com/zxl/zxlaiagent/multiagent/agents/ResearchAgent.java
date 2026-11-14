package com.zxl.zxlaiagent.multiagent.agents;

import com.zxl.zxlaiagent.agent.model.AgentExecutionEvent;
import com.zxl.zxlaiagent.knowledge.mcp.FormalKnowledgeMcpProtocol;
import com.zxl.zxlaiagent.multiagent.model.AgentCapability;
import com.zxl.zxlaiagent.multiagent.model.Task;
import com.zxl.zxlaiagent.multiagent.execution.TaskPromptRenderer;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;


import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Slf4j
public class ResearchAgent extends AbstractBaseAgent {

    private static final String RESEARCH_SYSTEM_PROMPT = """
            You are a research and analysis agent. You can:
            1. Analyze only the material provided in the execution context
            2. Analyze and synthesize information from multiple sources
            3. Generate comprehensive research reports
            4. Compare and contrast different options
            5. Provide data-driven insights and recommendations
            
            Do not invent sources, web searches or tool calls. Mark missing evidence explicitly. Answer concisely in the user language.
            """;

    private final TaskPromptRenderer renderer;
    private final com.zxl.zxlaiagent.research.ResearchRunner research;
    private volatile com.zxl.zxlaiagent.research.ScopedResearchFactory scopedResearchFactory;
    public void attachScopedResearchFactory(com.zxl.zxlaiagent.research.ScopedResearchFactory factory){
        scopedResearchFactory=java.util.Objects.requireNonNull(factory);
    }

    public ResearchAgent(ChatModel chatModel) {
        this(chatModel, new TaskPromptRenderer(new ObjectMapper()));
    }

    public ResearchAgent(ChatModel chatModel, TaskPromptRenderer renderer) {
        this(chatModel,renderer,null);
    }
    public ResearchAgent(ChatModel chatModel,TaskPromptRenderer renderer,com.zxl.zxlaiagent.research.ResearchRunner research) {
        super("research-1", "research", chatModel);
        this.renderer = renderer;
        this.research=research;
        this.capability = AgentCapability.builder()
                .agentType("research")
                .description("Information gathering, analysis, and research agent")
                .tools(research==null?Set.of():Set.of("knowledge__knowledge_lookup_v1"))
                .domains(Set.of("research", "analysis", "data", "writing"))
                .maxConcurrency(1)
                .canDelegate(false)
                .build();
    }

    @Override
    public CompletableFuture<String> processTask(Task task) {
        var captured=executionScope(task);
        return async(task, () -> {
            checkExecution(task);
            if(captured.scoped())return runScoped(task,captured);
            log.info("ResearchAgent executing: {}", task.getDescription());
            String result = research==null?callLlm(task, RESEARCH_SYSTEM_PROMPT, renderer.render(task)):
                research.run(renderer.render(task),remainingExecution(task,java.time.Duration.ofSeconds(30)),
                    ()->{checkExecution(task);return Thread.currentThread().isInterrupted()||task.snapshot().status()!=com.zxl.zxlaiagent.multiagent.model.TaskStatus.IN_PROGRESS;},
                    event->publishSafely(com.zxl.zxlaiagent.multiagent.model.AgentEvent.of(agentId,
                        event.type().equals("TOOL_REQUESTED")||event.type().equals("TOOL_STARTED")?com.zxl.zxlaiagent.multiagent.model.EventType.TOOL_CALLED:com.zxl.zxlaiagent.multiagent.model.EventType.TOOL_RESULT,
                        java.util.Map.of("taskId",task.getId(),"event",event))),
                    new com.zxl.zxlaiagent.research.ResearchRunner.ModelGate(){public <T>T call(java.util.function.Supplier<T> action){return controlledModelCall(task,action);}});
            log.info("ResearchAgent completed: {}", task.getDescription());
            return result;
        });
    }
    private String runScoped(Task task,com.zxl.zxlaiagent.multiagent.runtime.ExecutionRuntime.ExecutionScope scope){
        var context=scope.trustedContext();var factory=scopedResearchFactory;
        if(context==null||factory==null)throw new com.zxl.zxlaiagent.security.SecurityFailure("SCOPED_RESEARCH_UNAVAILABLE",503);
        scope.control().check();scope.boundary().check(com.zxl.zxlaiagent.security.Phase.DEQUEUE);
        var admission=new com.zxl.zxlaiagent.security.execution.OperationBoundary(){
            public void check(com.zxl.zxlaiagent.security.Phase phase){scope.control().check();}
            public <T>T start(com.zxl.zxlaiagent.security.Phase phase,java.util.function.Supplier<T> action){return scope.control().admit(action);}
        };
        var runner=factory.create(context.actor(),context.runId(),java.util.List.of(
                new com.zxl.zxlaiagent.security.AuthorizationService.Permission("run.readOwn",new com.zxl.zxlaiagent.security.ResourceRef("run",context.runId()))),admission);
        return runner.run(renderer.render(task),java.time.Duration.ofMillis(scope.control().waitMillis(java.time.Duration.ofSeconds(30))),
                ()->Thread.currentThread().isInterrupted()||scope.control().stopReason()!=null||task.snapshot().status()!=com.zxl.zxlaiagent.multiagent.model.TaskStatus.IN_PROGRESS,
                event->scope.control().admit(()->{
                    // ResearchRunner already holds its actor + knowledge + run publication gate.
                    // Never open a second PG gate inside that transaction.
                    deliverTaskEvent(scope,com.zxl.zxlaiagent.multiagent.model.AgentEvent.of(agentId,
                            event.type().equals("TOOL_REQUESTED")||event.type().equals("TOOL_STARTED")?com.zxl.zxlaiagent.multiagent.model.EventType.TOOL_CALLED:com.zxl.zxlaiagent.multiagent.model.EventType.TOOL_RESULT,
                            java.util.Map.of("taskId",task.getId(),"event",event.withIdentity(null,context.runId()))));
                    logToolResult(context.runId(), task.getId(), event);
                    return null;
                }),new com.zxl.zxlaiagent.research.ResearchRunner.ModelGate(){
                    public <T>T call(java.util.function.Supplier<T> action){return controlledModelCall(task,scope,action);}
                });
    }

    private static void logToolResult(String runId, String taskId, AgentExecutionEvent event) {
        String outcome = switch (event.type()) {
            case "TOOL_COMPLETED" -> "completed";
            case "TOOL_FAILED" -> "failed";
            default -> null;
        };
        if (outcome == null || event.durationMs() == null || !log.isInfoEnabled()) return;
        log.info("Agent tool finished runId={} taskId={} callRef={} tool={} outcome={} durationMs={}",
                runId, diagnosticTaskId(taskId), toolCallReference(runId, taskId, event.callId()),
                FormalKnowledgeMcpProtocol.EXPOSED_NAME, outcome, event.durationMs());
    }

    private static String diagnosticTaskId(String taskId) {
        try {
            return UUID.fromString(taskId).toString();
        } catch (IllegalArgumentException | NullPointerException ignored) {
            return "non-uuid";
        }
    }

    private static String toolCallReference(String runId, String taskId, String modelCallId) {
        try {
            String input = runId + "\n" + taskId + "\n" + modelCallId;
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
