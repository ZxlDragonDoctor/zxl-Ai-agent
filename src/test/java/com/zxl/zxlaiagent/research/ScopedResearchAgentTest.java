package com.zxl.zxlaiagent.research;

import com.fasterxml.jackson.databind.*;
import com.zxl.zxlaiagent.multiagent.agents.*;
import com.zxl.zxlaiagent.multiagent.bus.AgentMessageBus;
import com.zxl.zxlaiagent.multiagent.execution.*;
import com.zxl.zxlaiagent.multiagent.model.*;
import com.zxl.zxlaiagent.multiagent.runtime.*;
import com.zxl.zxlaiagent.security.*;
import com.zxl.zxlaiagent.security.execution.OperationBoundary;
import com.zxl.zxlaiagent.knowledge.mcp.FormalKnowledgeMcpProtocol;
import com.zxl.zxlaiagent.tools.support.ToolResults;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScopedResearchAgentTest {
    static final ActorRef ACTOR=new ActorRef("synthetic-subject","synthetic-tenant","synthetic-session",1);
    static class Fixture implements AutoCloseable {
        final ExecutionRuntime runtime=new ExecutionRuntime(new RuntimeSettings());
        final WorkflowRun run=new WorkflowRun("synthetic research",r->{});
        final Task task=Task.create("synthetic research");
        final PrivateRunEvents privateEvents=new PrivateRunEvents();
        List<AgentEvent> events(){return privateEvents.snapshot();}
        final AgentMessageBus global=mock(AgentMessageBus.class);
        final ScopedResearchFactory factory=mock(ScopedResearchFactory.class);
        final ResearchRunner legacy=mock(ResearchRunner.class);
        final AtomicInteger toolCalls=new AtomicInteger();
        Fixture(OperationBoundary boundary){this(boundary,ACTOR);}
        Fixture(OperationBoundary boundary,ActorRef actor){
            var registry=new RunRegistry(new RuntimeSettings());
            var entry=registry.register(run,new ExecutionControl(System.currentTimeMillis(),Duration.ofSeconds(5),System::nanoTime),
                    System.nanoTime(),boundary,new TrustedRunContext(actor,run.id,privateEvents));
            task.setInput(Map.of());runtime.bind(task,entry);
        }
        ResearchAgent agent(){
            var agent=new ResearchAgent(mock(ChatModel.class),new TaskPromptRenderer(new ObjectMapper()),legacy);
            agent.attachRuntime(runtime);agent.attachMessageBus(global);agent.requireScopedExecution();agent.attachScopedResearchFactory(factory);return agent;
        }
        ResearchRunner runner(){return runner(()->{});}
        ResearchRunner runner(Runnable beforeAnswer){return runner(beforeAnswer,OperationBoundary.unrestricted(),()->{});}
        ResearchRunner runner(Runnable beforeAnswer,OperationBoundary researchBoundary,Runnable afterTool){
            var spec=FormalKnowledgeMcpProtocol.modelDefinition();
            var gateway=new ResearchRunner.Gateway(){
                public List<ResearchRunner.Tool> discover(Duration budget,BooleanSupplier cancelled){
                    return List.of(new ResearchRunner.Tool(spec.name(),spec.description(),ToolResults.json(spec.inputSchema())));
                }
                public String call(String name,String input,Duration budget,BooleanSupplier cancelled){
                    toolCalls.incrementAndGet();afterTool.run();return "{\"success\":true,\"code\":\"OK\",\"message\":\"done\",\"data\":{\"hits\":[{\"documentId\":\"synthetic-doc\",\"chunkId\":\"c\",\"text\":\"synthetic evidence\"}]}}";
                }
            };
            var model=new ResearchRunner.Model(){
                public ResearchRunner.Selection select(String question,List<ResearchRunner.Tool> tools,Duration budget,BooleanSupplier cancelled){return new ResearchRunner.Selection("synthetic-call",spec.name(),"{\"query\":\"synthetic\"}");}
                public String answer(String question,List<JsonNode> evidence,Duration budget,BooleanSupplier cancelled){beforeAnswer.run();return "synthetic answer [C1]";}
            };
            return ResearchRunner.authorized(gateway,model,(hits,budget,cancelled)->hits,researchBoundary);
        }
        public void close(){runtime.close();}
    }
    @Test void toolReturnRevocationPreventsPrivateResultAndAnswer() throws Exception {
        var allowed=new AtomicBoolean(true);
        OperationBoundary boundary=new OperationBoundary(){
            public void check(Phase phase){if(!allowed.get())throw new SecurityFailure("ACTION_DENIED",403);}
            public <T>T start(Phase phase,Supplier<T> work){check(phase);return work.get();}
        };
        try(var fixture=new Fixture(boundary)){
            when(fixture.factory.create(eq(ACTOR),eq(fixture.run.id),anyList(),any(OperationBoundary.class)))
                    .thenReturn(fixture.runner(()->fail("No synthesis after revocation"),boundary,()->allowed.set(false)));
            assertThrows(ExecutionException.class,()->fixture.agent().executeTask(fixture.task).get(3,TimeUnit.SECONDS));
            assertEquals(1,fixture.task.snapshot().modelCalls());assertEquals(1,fixture.toolCalls.get());
            assertTrue(fixture.events().stream().noneMatch(e->e.getType()==EventType.TOOL_RESULT||e.getType()==EventType.TASK_COMPLETED));
            verifyNoInteractions(fixture.global);
        }
    }
    @Test void cancellationAfterToolReturnPreventsResultPublicationAndSynthesis() throws Exception {
        try(var fixture=new Fixture(OperationBoundary.unrestricted())){
            when(fixture.factory.create(eq(ACTOR),eq(fixture.run.id),anyList(),any(OperationBoundary.class)))
                    .thenReturn(fixture.runner(()->fail("No synthesis after cancellation"),OperationBoundary.unrestricted(),()->fixture.runtime.control(fixture.task).cancel()));
            assertThrows(ExecutionException.class,()->fixture.agent().executeTask(fixture.task).get(3,TimeUnit.SECONDS));
            assertEquals(TaskStatus.CANCELLED,fixture.task.snapshot().status());assertEquals(1,fixture.task.snapshot().modelCalls());
            assertTrue(fixture.events().stream().noneMatch(e->e.getType()==EventType.TOOL_RESULT||e.getType()==EventType.TASK_COMPLETED));
            verifyNoInteractions(fixture.global);
        }
    }
    @Test void trustedResearchUsesFormalFactoryAndPrivateTaskAndToolEvents() throws Exception {
        try(var fixture=new Fixture(OperationBoundary.unrestricted())){
            when(fixture.factory.create(eq(ACTOR),eq(fixture.run.id),anyList(),any(OperationBoundary.class))).thenReturn(fixture.runner());
            assertTrue(fixture.agent().executeTask(fixture.task).get(3,TimeUnit.SECONDS).contains("synthetic answer [C1]"));
            verify(fixture.factory).create(eq(ACTOR),eq(fixture.run.id),eq(List.of(new AuthorizationService.Permission("run.readOwn",new ResourceRef("run",fixture.run.id)))),any(OperationBoundary.class));
            verifyNoInteractions(fixture.legacy);verifyNoInteractions(fixture.global);
            assertEquals(2,fixture.task.snapshot().modelCalls());assertEquals(1,fixture.toolCalls.get());
            assertTrue(fixture.events().stream().anyMatch(e->e.getType()==EventType.TOOL_RESULT));
            assertTrue(fixture.events().stream().anyMatch(e->e.getType()==EventType.TASK_COMPLETED));
            assertTrue(fixture.events().stream().allMatch(e->fixture.run.id.equals(e.getData().get("executionId"))));
        }
    }
    @Test void toolOutcomeLogsTrustedCorrelationWithoutLoggingPayloadOrRawModelIdentifier() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ResearchAgent.class);
        var logs = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);
        try (var fixture = new Fixture(OperationBoundary.unrestricted())) {
            when(fixture.factory.create(eq(ACTOR),eq(fixture.run.id),anyList(),any(OperationBoundary.class)))
                    .thenReturn(fixture.runner());
            fixture.agent().executeTask(fixture.task).get(3, TimeUnit.SECONDS);
            var message = logs.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .filter(log -> log.startsWith("Agent tool finished ")).findFirst().orElseThrow();
            assertTrue(message.contains("runId=" + fixture.run.id));
            assertTrue(message.contains("taskId=" + fixture.task.getId()));
            assertTrue(message.matches(".* callRef=[0-9a-f]{64} .*outcome=completed durationMs=[0-9]+"));
            assertFalse(message.contains("synthetic-call"));
            assertFalse(message.contains("synthetic research"));
            assertFalse(message.contains("synthetic evidence"));
            assertFalse(message.contains(ACTOR.subjectId()));
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }
    @Test void formalFactoryMissingCannotInvokeLegacyRunner() throws Exception {
        try(var fixture=new Fixture(OperationBoundary.unrestricted())){
            var agent=new ResearchAgent(mock(ChatModel.class),new TaskPromptRenderer(new ObjectMapper()),fixture.legacy);
            agent.attachRuntime(fixture.runtime);agent.attachMessageBus(fixture.global);agent.requireScopedExecution();
            assertThrows(ExecutionException.class,()->agent.executeTask(fixture.task).get(2,TimeUnit.SECONDS));
            verifyNoInteractions(fixture.legacy);verifyNoInteractions(fixture.global);
        }
    }
    @Test void revokedCompletionDoesNotPublishOrReturnSensitiveResult() throws Exception {
        var allowed=new AtomicBoolean(true);
        OperationBoundary boundary=new OperationBoundary(){
            public void check(Phase phase){if(!allowed.get())throw new SecurityFailure("ACTION_DENIED",403);}
            public <T>T start(Phase phase,Supplier<T> work){check(phase);return work.get();}
        };
        try(var fixture=new Fixture(boundary)){
            var agent=new AbstractBaseAgent("synthetic-agent","code",mock(ChatModel.class)){
                public CompletableFuture<String> processTask(Task task){allowed.set(false);return CompletableFuture.completedFuture("sensitive synthetic result");}
            };
            agent.attachRuntime(fixture.runtime);agent.attachMessageBus(fixture.global);agent.requireScopedExecution();
            assertThrows(ExecutionException.class,()->agent.executeTask(fixture.task).get(2,TimeUnit.SECONDS));
            assertNotEquals(TaskStatus.COMPLETED,fixture.task.snapshot().status());
            assertTrue(fixture.events().stream().noneMatch(e->e.getType()==EventType.TASK_COMPLETED));
            verifyNoInteractions(fixture.global);
        }
    }
}
