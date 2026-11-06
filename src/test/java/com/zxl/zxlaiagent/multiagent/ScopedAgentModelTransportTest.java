package com.zxl.zxlaiagent.multiagent;

import com.zxl.zxlaiagent.multiagent.agents.AbstractBaseAgent;
import com.zxl.zxlaiagent.multiagent.execution.WorkflowRun;
import com.zxl.zxlaiagent.multiagent.model.*;
import com.zxl.zxlaiagent.multiagent.runtime.*;
import com.zxl.zxlaiagent.agent.stream.DashScopeAgentStreamer;
import com.zxl.zxlaiagent.security.SecurityFailure;
import com.zxl.zxlaiagent.security.execution.OperationBoundary;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ScopedAgentModelTransportTest {
    @Test void cancellationInsideAuthorityGatePreventsTheSubscriptionAction(){
        try(var runtime=new ExecutionRuntime(new RuntimeSettings())){
            var control=new java.util.concurrent.atomic.AtomicReference<ExecutionControl>();
            var subscriptions=new java.util.concurrent.atomic.AtomicInteger();
            OperationBoundary boundary=new OperationBoundary(){
                public void check(com.zxl.zxlaiagent.security.Phase phase){}
                public <T>T start(com.zxl.zxlaiagent.security.Phase phase,java.util.function.Supplier<T> action){
                    control.get().cancel();return action.get();
                }
            };
            var task=bind(runtime,boundary);control.set(runtime.control(task));
            var agent=new Agent();agent.attachRuntime(runtime);var streamer=mock(DashScopeAgentStreamer.class);agent.attachScopedStreamer(streamer);
            when(streamer.stream(anyString(),anyList(),any(),any(),any(),any())).thenAnswer(invocation->{
                OperationBoundary dispatched=invocation.getArgument(5);
                return dispatched.start(com.zxl.zxlaiagent.security.Phase.MODEL_DISPATCH,()->{
                    subscriptions.incrementAndGet();return new ChatResponse(List.of(new Generation(new AssistantMessage("late"))));
                });
            });
            assertThrows(WorkflowException.class,()->agent.ask(task));assertEquals(0,subscriptions.get());
        }
    }
    @Test void unbindAfterCaptureCannotDetachTheOriginalModelLease(){
        try(var runtime=new ExecutionRuntime(new RuntimeSettings())){
            var task=bind(runtime,OperationBoundary.unrestricted());var captured=runtime.executionScope(task);
            var agent=new Agent(){
                @Override protected ExecutionRuntime.ExecutionScope executionScope(Task current){
                    var scope=super.executionScope(current);runtime.unbind(current);return scope;
                }
            };
            agent.attachRuntime(runtime);var streamer=mock(DashScopeAgentStreamer.class);agent.attachScopedStreamer(streamer);
            when(streamer.stream(anyString(),anyList(),any(),any(),any(),any())).thenAnswer(invocation->{
                assertEquals(1,captured.entry().view().activeModelCalls());
                return new ChatResponse(List.of(new Generation(new AssistantMessage("captured"))));
            });
            assertEquals("captured",agent.ask(task));assertEquals(0,captured.entry().view().activeModelCalls());
        }
    }
    @Test void actualAgentTransportStartsUnderTheRunBoundary() throws Exception {
        verifyActualTransport(false);
    }
    @Test void deniedActualSubscriptionMakesNoHttpRequest() throws Exception {
        verifyActualTransport(true);
    }
    void verifyActualTransport(boolean denyStart) throws Exception {
        var requests=new java.util.concurrent.atomic.AtomicInteger();
        var starts=new java.util.concurrent.atomic.AtomicInteger();
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat/completions",exchange->{
            requests.incrementAndGet();
            byte[] body=("data: {\"choices\":[{\"delta\":{\"content\":\"synthetic response\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try {
                exchange.getResponseHeaders().set("Content-Type","text/event-stream");
                exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
            } finally {exchange.close();}
        });
        server.start();
        try(var runtime=new ExecutionRuntime(new RuntimeSettings())){
            OperationBoundary boundary=new OperationBoundary(){
                public void check(com.zxl.zxlaiagent.security.Phase phase){}
                public <T>T start(com.zxl.zxlaiagent.security.Phase phase,java.util.function.Supplier<T> action){
                    assertEquals(com.zxl.zxlaiagent.security.Phase.MODEL_DISPATCH,phase);starts.incrementAndGet();
                    if(denyStart)throw new SecurityFailure("SYNTHETIC_DENIAL",403);
                    return action.get();
                }
            };
            var agent=new Agent();agent.attachRuntime(runtime);agent.requireScopedExecution();
            agent.attachScopedStreamer(new DashScopeAgentStreamer(new com.fasterxml.jackson.databind.ObjectMapper(),"synthetic-token",
                    "http://127.0.0.1:"+server.getAddress().getPort(),"synthetic-model",false,0));
            var task=bind(runtime,boundary);
            if(denyStart)assertThrows(SecurityFailure.class,()->agent.ask(task));
            else assertEquals("synthetic response",agent.ask(task));
            assertEquals(1,starts.get());assertEquals(denyStart?0:1,requests.get());assertEquals(0,agent.legacyCalls);
        } finally {server.stop(0);}
    }
    @Test void scopedResearchCannotInvokeTheLegacyRunner(){
        try(var runtime=new ExecutionRuntime(new RuntimeSettings())){
            var runner=mock(com.zxl.zxlaiagent.research.ResearchRunner.class);
            var agent=new com.zxl.zxlaiagent.multiagent.agents.ResearchAgent(mock(ChatModel.class),
                    new com.zxl.zxlaiagent.multiagent.execution.TaskPromptRenderer(new com.fasterxml.jackson.databind.ObjectMapper()),runner);
            agent.attachRuntime(runtime);
            var task=bind(runtime,OperationBoundary.unrestricted());
            var error=assertThrows(java.util.concurrent.CompletionException.class,()->agent.processTask(task).join());
            assertInstanceOf(SecurityFailure.class,error.getCause());verifyNoInteractions(runner);
        }
    }
    @Test void formalAgentWithNoTrustedRunCannotUseLegacyTransport(){
        var agent=new Agent();agent.requireScopedExecution();var task=Task.create("synthetic");TaskLifecycle.start(task,"synthetic");
        assertThrows(SecurityFailure.class,()->agent.ask(task));assertEquals(0,agent.legacyCalls);
    }
    static class Agent extends AbstractBaseAgent {
        int legacyCalls;
        Agent(){super("synthetic","synthetic",mock(ChatModel.class));}
        public CompletableFuture<String> processTask(Task task){return CompletableFuture.completedFuture("unused");}
        protected String callLlm(String system,String user){legacyCalls++;return "legacy";}
        String ask(Task task){return callLlm(task,"system","question");}
    }
    Task bind(ExecutionRuntime runtime,OperationBoundary boundary){
        var settings=new RuntimeSettings();var registry=new RunRegistry(settings);
        var entry=registry.register(new WorkflowRun("synthetic",r->{}),new ExecutionControl(System.currentTimeMillis(),Duration.ofSeconds(5),System::nanoTime),System.nanoTime(),boundary);
        var task=Task.create("synthetic");TaskLifecycle.start(task,"synthetic");runtime.bind(task,entry);return task;
    }
    @Test void scopedModelCannotFallBackToBlockingLegacyTransport(){
        try(var runtime=new ExecutionRuntime(new RuntimeSettings())){
            var agent=new Agent();agent.attachRuntime(runtime);var task=bind(runtime,OperationBoundary.unrestricted());
            assertThrows(SecurityFailure.class,()->agent.ask(task));assertEquals(0,agent.legacyCalls);
        }
    }
    @Test void scopedModelUsesScopedStreamerInsteadOfLegacyTransport(){
        try(var runtime=new ExecutionRuntime(new RuntimeSettings())){
            var agent=new Agent();agent.attachRuntime(runtime);var boundary=OperationBoundary.unrestricted();var task=bind(runtime,boundary);
            var streamer=mock(DashScopeAgentStreamer.class);agent.attachScopedStreamer(streamer);
            when(streamer.stream(anyString(),anyList(),any(),any(),any(),any(OperationBoundary.class)))
                    .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("scoped")))));
            assertEquals("scoped",agent.ask(task));assertEquals(0,agent.legacyCalls);
            verify(streamer).stream(eq("system"),anyList(),any(),any(),any(),any(OperationBoundary.class));
        }
    }
    @Test void legacyTasksKeepTheirExplicitClassroomTransport(){
        var agent=new Agent();var task=Task.create("synthetic");TaskLifecycle.start(task,"synthetic");
        assertEquals("legacy",agent.ask(task));assertEquals(1,agent.legacyCalls);
    }
}
