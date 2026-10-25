package com.zxl.zxlaiagent.multiagent.agents;

import com.zxl.zxlaiagent.multiagent.model.AgentCapability;
import com.zxl.zxlaiagent.multiagent.model.Task;
import com.zxl.zxlaiagent.multiagent.execution.TaskPromptRenderer;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;


import java.util.Set;
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
        return async(task, () -> {
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
}
