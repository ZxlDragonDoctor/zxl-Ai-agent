package com.zxl.zxlaiagent.multiagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zxl.zxlaiagent.multiagent.agents.AbstractBaseAgent;
import com.zxl.zxlaiagent.multiagent.bus.AgentMessageBus;
import com.zxl.zxlaiagent.multiagent.execution.*;
import com.zxl.zxlaiagent.multiagent.model.*;
import com.zxl.zxlaiagent.multiagent.orchestration.OrchestratorAgent;
import com.zxl.zxlaiagent.multiagent.runtime.*;
import com.zxl.zxlaiagent.security.*;
import com.zxl.zxlaiagent.security.execution.OperationBoundary;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.model.ChatModel;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScopedOrchestrationTest {
    final ChatModel model=mock(ChatModel.class);
    final AgentMessageBus bus=mock(AgentMessageBus.class);
    final ActorRef actor=new ActorRef("synthetic","tenant","session",1);
    final PrivateRunEvents events=new PrivateRunEvents();
    final OrchestratorAgent orchestrator=new OrchestratorAgent(model);
    @BeforeEach void setup(){orchestrator.init(bus);}
    @AfterEach void close(){orchestrator.shutdown();}
    OrchestratorAgent.ScopedSubmission submission(String id,OperationBoundary boundary){return new OrchestratorAgent.ScopedSubmission(boundary,new TrustedRunContext(actor,id,events));}
    void worker(String role,Function<Task,CompletableFuture<String>> action){
        var agent=new AbstractBaseAgent(role,role,model){public CompletableFuture<String> processTask(Task task){return action.apply(task);}};
        agent.attachMessageBus(bus);orchestrator.registerAgent(agent);
    }
    String plan(Task planning){
        var plan=Plan.create(planning.getDescription());var task=Task.create("read synthetic");task.setId("same-model-task-id");task.setInput(Map.of("capability","research"));plan.addTask(task);
        try{return new ObjectMapper().writeValueAsString(plan);}catch(Exception e){throw new RuntimeException(e);}
    }
    @Test void trustedFactoryCompletesBeforeAnyWorkAndAllEventsArePrivate() throws Exception {
        var trusted=new AtomicBoolean();
        worker("planner",task->{assertTrue(trusted.get());return CompletableFuture.completedFuture(plan(task));});
        worker("research",task->CompletableFuture.completedFuture("synthetic answer"));clearInvocations(bus);
        var handle=orchestrator.submitScoped("synthetic goal",Map.of(),5000L,id->{trusted.set(true);return submission(id,OperationBoundary.unrestricted());});
        assertTrue(handle.result().get(3,TimeUnit.SECONDS).successful());
        var view=orchestrator.runStatusScoped(handle.executionId());assertNotNull(view.run());assertFalse(view.events().isEmpty());
        assertTrue(view.events().stream().allMatch(event->handle.executionId().equals(event.getData().get("executionId"))));
        verify(bus,never()).publishEvent(any());
    }
    @Test void rejectedOwnerRegistrationNeverQueuesOrPublishes(){
        clearInvocations(bus);
        assertThrows(SecurityFailure.class,()->orchestrator.submitScoped("secret",Map.of(),5000L,id->{throw new SecurityFailure("ACTION_DENIED",403);}));
        assertTrue(events.snapshot().isEmpty());verify(bus,never()).publishEvent(any());
    }
    @Test void retainedCandidatesAreBoundedIndependentAndExcludeLegacyRuns() throws Exception {
        assertThrows(IllegalArgumentException.class,()->orchestrator.retainedScopedRunIds(0));
        assertThrows(IllegalArgumentException.class,()->orchestrator.retainedScopedRunIds(201));
        var legacy=orchestrator.submit("legacy",Map.of(),5000L,report->{});legacy.result().get(2,TimeUnit.SECONDS);
        var first=orchestrator.submitScoped("first",Map.of(),5000L,id->submission(id,OperationBoundary.unrestricted()));first.result().get(2,TimeUnit.SECONDS);
        var snapshot=orchestrator.retainedScopedRunIds(200);assertEquals(List.of(first.executionId()),snapshot);
        var second=orchestrator.submitScoped("second",Map.of(),5000L,id->submission(id,OperationBoundary.unrestricted()));second.result().get(2,TimeUnit.SECONDS);
        assertEquals(List.of(first.executionId()),snapshot);assertEquals(1,orchestrator.retainedScopedRunIds(1).size());
        assertEquals(Set.of(first.executionId(),second.executionId()),new HashSet<>(orchestrator.retainedScopedRunIds(200)));
        assertThrows(UnsupportedOperationException.class,()->snapshot.add("extra"));
        assertFalse(orchestrator.retainedScopedRunIds(200).contains(legacy.executionId()));
    }
    @Test void invalidFormalInputCannotCreateOwnerOrPublishQueuedReport(){
        var registrations=new AtomicInteger();
        Function<String,OrchestratorAgent.ScopedSubmission> factory=id->{registrations.incrementAndGet();return submission(id,OperationBoundary.unrestricted());};
        assertThrows(WorkflowException.class,()->orchestrator.submitScoped(" ",Map.of(),5000L,factory));
        assertThrows(WorkflowException.class,()->orchestrator.submitScoped("x".repeat(12001),Map.of(),5000L,factory));
        assertThrows(WorkflowException.class,()->orchestrator.submitScoped("goal",Map.of("payload","x".repeat(32001)),5000L,factory));
        assertEquals(0,registrations.get());assertTrue(events.snapshot().isEmpty());
    }
    @Test void reportDenialDoesNotCommitSensitiveLatestOrOutcome(){
        var run=new WorkflowRun("sensitive goal",report->fail("legacy observer must not be called"));
        OperationBoundary deny=new OperationBoundary(){public void check(Phase phase){throw new SecurityFailure("ACTION_DENIED",403);}public <T>T start(Phase phase,Supplier<T> action){check(phase);return action.get();}};
        run.configureScoped(deny,new TrustedRunContext(actor,run.id,events));
        assertThrows(SecurityFailure.class,()->run.emit("queued",null,null));assertNull(run.latestReport());
        assertThrows(SecurityFailure.class,()->run.finishFailure(new WorkflowError("SYNTHETIC","workflow","sensitive")));assertNull(run.outcome());
        assertNull(run.finishDenied().goal());assertTrue(events.snapshot().isEmpty());assertNull(run.latestReport().planningTask());
    }
    @Test void cancellationInsideFinalAuthorityGateCannotCommitSuccess(){
        var control=new ExecutionControl(System.currentTimeMillis(),java.time.Duration.ofSeconds(5),System::nanoTime);
        var run=new WorkflowRun("goal",report->fail("legacy observer"));
        var plan=Plan.create("goal");var task=Task.create("synthetic");plan.addTask(task);run.plan=plan;
        TaskLifecycle.start(run.planningTask,"planner");TaskLifecycle.complete(run.planningTask,"plan");TaskLifecycle.start(task,"research");TaskLifecycle.complete(task,"evidence");
        OperationBoundary boundary=new OperationBoundary(){public void check(Phase phase){}public <T>T start(Phase phase,Supplier<T> action){control.cancel();return action.get();}};
        run.configureScoped(boundary,new TrustedRunContext(actor,run.id,events),control);
        assertThrows(WorkflowException.class,()->run.finishSuccess("sensitive result"));assertNull(run.outcome());assertNull(run.latestReport());assertTrue(events.snapshot().isEmpty());
    }
    @Test void deniedStageReportRetainsActualLeaseUntilTheWorkerReturns() throws Exception {
        var allowed=new AtomicBoolean(true);var work=new CompletableFuture<String>();
        OperationBoundary boundary=new OperationBoundary(){public void check(Phase phase){if(!allowed.get())throw new SecurityFailure("ACTION_DENIED",403);}public <T>T start(Phase phase,Supplier<T> action){check(phase);return action.get();}};
        worker("planner",task->{allowed.set(false);return work;});
        var handle=orchestrator.submitScoped("secret",Map.of(),5000L,id->submission(id,boundary));
        var outcome=handle.result().get(2,TimeUnit.SECONDS);assertFalse(outcome.successful());
        assertTrue(orchestrator.runStatusScoped(handle.executionId()).run().runtime().cleanupPending());
        work.complete("late secret");
        var until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(orchestrator.runStatusScoped(handle.executionId()).run().runtime().cleanupPending()&&System.nanoTime()<until)Thread.yield();
        assertFalse(orchestrator.runStatusScoped(handle.executionId()).run().runtime().cleanupPending());assertNull(handle.result().get().result());
    }
    @Test void sameModelTaskIdsAcrossRunsDoNotOverwritePendingOwnership() throws Exception {
        var started=new CountDownLatch(2);var code=new CompletableFuture<String>();var research=new CompletableFuture<String>();
        worker("planner",task->{
            var plan=Plan.create(task.getDescription());plan.setId("same-model-plan-id");var child=Task.create("synthetic");child.setId("same-model-task-id");
            child.setInput(Map.of("capability",task.getDescription()));plan.addTask(child);
            try{return CompletableFuture.completedFuture(new ObjectMapper().writeValueAsString(plan));}catch(Exception e){throw new RuntimeException(e);}
        });
        worker("code",task->{started.countDown();return code;});worker("research",task->{started.countDown();return research;});
        var first=orchestrator.submitScoped("code",Map.of(),5000L,id->submission(id,OperationBoundary.unrestricted()));
        var second=orchestrator.submitScoped("research",Map.of(),5000L,id->submission(id,OperationBoundary.unrestricted()));
        assertTrue(started.await(3,TimeUnit.SECONDS));
        // processTask enters before startWork has received and registered the returned Future.
        var registeredBy=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(!Integer.valueOf(2).equals(orchestrator.getStatus().get("pendingTasks"))&&System.nanoTime()<registeredBy)Thread.yield();
        assertEquals(2,orchestrator.getStatus().get("pendingTasks"));assertEquals(2,orchestrator.getStatus().get("activePlans"));
        code.complete("code result");assertTrue(first.result().get(2,TimeUnit.SECONDS).successful());
        assertEquals(1,orchestrator.getStatus().get("pendingTasks"));research.complete("research result");assertTrue(second.result().get(2,TimeUnit.SECONDS).successful());
    }
    @Test void revokedRunCompletesNeutralOutcomeAndReleasesFuture() throws Exception {
        var allowed=new AtomicBoolean(true);var began=new CountDownLatch(1);var work=new CompletableFuture<String>();
        OperationBoundary boundary=new OperationBoundary(){public void check(Phase phase){if(!allowed.get())throw new SecurityFailure("ACTION_DENIED",403);}public <T>T start(Phase phase,Supplier<T> action){check(phase);return action.get();}};
        worker("planner",task->{began.countDown();return work;});clearInvocations(bus);
        var handle=orchestrator.submitScoped("secret goal",Map.of(),5000L,id->submission(id,boundary));
        assertTrue(began.await(2,TimeUnit.SECONDS));allowed.set(false);work.complete("secret result");
        var outcome=handle.result().get(2,TimeUnit.SECONDS);assertFalse(outcome.successful());assertNull(outcome.goal());assertNull(outcome.result());assertTrue(outcome.tasks().isEmpty());assertNull(outcome.planningTask());
        assertNull(orchestrator.runStatusScoped(handle.executionId()).run().report().goal());verify(bus,never()).publishEvent(any());
    }
    @Test void failedTerminalAuthorityCommitCannotExposeSuccessOrLeaveResultHanging() throws Exception {
        OperationBoundary boundary=new OperationBoundary(){
            public void check(Phase phase){}
            public <T>T start(Phase phase,Supplier<T> action){
                T value=action.get();
                if(value instanceof WorkflowOutcome outcome&&outcome.successful())throw new IllegalStateException("synthetic commit failure");
                return value;
            }
        };
        worker("planner",task->CompletableFuture.completedFuture(plan(task)));worker("research",task->CompletableFuture.completedFuture("sensitive result"));
        var handle=orchestrator.submitScoped("secret goal",Map.of(),5000L,id->submission(id,boundary));
        var outcome=handle.result().get(2,TimeUnit.SECONDS);
        assertFalse(outcome.successful());assertNull(outcome.goal());assertNull(outcome.result());
        var view=orchestrator.runStatusScoped(handle.executionId());assertNotEquals("completed",view.run().report().status());
        assertTrue(view.events().stream().filter(event->event.getData().get("report") instanceof WorkflowRun.Report)
                .map(event->(WorkflowRun.Report)event.getData().get("report")).noneMatch(report->report.status().equals("completed")));
    }
    @Test void terminalCandidateAndEventStayInvisibleUntilAuthorityReturns(){
        var run=new WorkflowRun("goal",report->fail("legacy observer"));
        var plan=Plan.create("goal");var task=Task.create("work");plan.addTask(task);run.plan=plan;
        TaskLifecycle.start(run.planningTask,"planner");TaskLifecycle.complete(run.planningTask,"plan");TaskLifecycle.start(task,"research");TaskLifecycle.complete(task,"evidence");
        OperationBoundary boundary=new OperationBoundary(){
            public void check(Phase phase){}
            public <T>T start(Phase phase,Supplier<T> action){T value=action.get();assertNull(run.outcome());assertNull(run.latestReport());assertTrue(events.snapshot().isEmpty());return value;}
        };
        run.configureScoped(boundary,new TrustedRunContext(actor,run.id,events));
        var outcome=run.finishSuccess("answer");assertSame(outcome,run.outcome());assertSame(outcome,run.latestReport().outcome());assertEquals(1,events.snapshot().size());
    }
    @Test void cancellationWaitsForTerminalCommitAndCannotAcknowledgeCancellationAfterSuccess() throws Exception {
        var enteringCommit=new CountDownLatch(1);var releaseCommit=new CountDownLatch(1);
        OperationBoundary boundary=new OperationBoundary(){
            public void check(Phase phase){}
            public <T>T start(Phase phase,Supplier<T> action){
                T value=action.get();if(value instanceof WorkflowOutcome outcome&&outcome.successful()){
                    enteringCommit.countDown();try{assertTrue(releaseCommit.await(3,TimeUnit.SECONDS));}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new RuntimeException(interrupted);}
                }return value;
            }
        };
        worker("planner",task->CompletableFuture.completedFuture(plan(task)));worker("research",task->CompletableFuture.completedFuture("answer"));
        var handle=orchestrator.submitScoped("goal",Map.of(),5000L,id->submission(id,boundary));var cancelExecutor=Executors.newSingleThreadExecutor();
        try{assertTrue(enteringCommit.await(2,TimeUnit.SECONDS));
            var cancellation=cancelExecutor.submit(()->orchestrator.cancelScoped(handle.executionId(),OperationBoundary.unrestricted()));
            assertThrows(TimeoutException.class,()->cancellation.get(100,TimeUnit.MILLISECONDS));releaseCommit.countDown();
            assertTrue(handle.result().get(2,TimeUnit.SECONDS).successful());assertEquals("already_finished",cancellation.get(2,TimeUnit.SECONDS).status());
        }finally{releaseCommit.countDown();cancelExecutor.shutdownNow();}
    }
    @Test void scopedCancellationDoesNotNestReadGateInsideWriteGate() throws Exception {
        var inWrite=new AtomicBoolean();var began=new CountDownLatch(1);var work=new CompletableFuture<String>();
        OperationBoundary read=new OperationBoundary(){public void check(Phase phase){assertFalse(inWrite.get());}public <T>T start(Phase phase,Supplier<T> action){check(phase);return action.get();}};
        worker("planner",task->{began.countDown();return work;});
        var handle=orchestrator.submitScoped("goal",Map.of(),5000L,id->submission(id,read));assertTrue(began.await(2,TimeUnit.SECONDS));
        OperationBoundary write=new OperationBoundary(){public void check(Phase phase){}public <T>T start(Phase phase,Supplier<T> action){inWrite.set(true);try{return action.get();}finally{inWrite.set(false);}}};
        assertEquals("accepted",orchestrator.cancelScoped(handle.executionId(),write).status());assertEquals("cancelled",handle.result().get(2,TimeUnit.SECONDS).status());work.complete("late");
    }
}
