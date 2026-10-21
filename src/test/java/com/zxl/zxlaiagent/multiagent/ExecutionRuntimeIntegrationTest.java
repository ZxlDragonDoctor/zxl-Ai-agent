package com.zxl.zxlaiagent.multiagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zxl.zxlaiagent.multiagent.agents.*;
import com.zxl.zxlaiagent.multiagent.bus.AgentMessageBus;
import com.zxl.zxlaiagent.multiagent.execution.*;
import com.zxl.zxlaiagent.multiagent.model.*;
import com.zxl.zxlaiagent.multiagent.orchestration.OrchestratorAgent;
import com.zxl.zxlaiagent.multiagent.runtime.*;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.model.ChatModel;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExecutionRuntimeIntegrationTest {
    final ChatModel model = mock(ChatModel.class);
    final AgentMessageBus bus = mock(AgentMessageBus.class);
    final RuntimeSettings settings = new RuntimeSettings();
    final AtomicLong clock = new AtomicLong(System.nanoTime());
    OrchestratorAgent orchestrator;
    final List<WorkflowRun.Report> reports = new CopyOnWriteArrayList<>();
    static final String DAG = """
            [{"task_description":"A","dependencies":[],"required_capability":"research"},
             {"task_description":"B","dependencies":[],"required_capability":"code"},
             {"task_description":"C","dependencies":[0,1],"required_capability":"supervisor"}]
            """;
    @BeforeEach void init() { orchestrator = new OrchestratorAgent(model, settings, clock::get); orchestrator.init(bus); }
    @AfterEach void cleanup() { orchestrator.shutdown(); }
    void planner(String json) { register(new PlannerAgent(model) { @Override protected String callLlm(String s, String p) { return json; } }); }
    void register(AbstractBaseAgent agent) { agent.attachMessageBus(bus); orchestrator.registerAgent(agent); }
    AbstractBaseAgent worker(String role, Function<Task, CompletableFuture<String>> action) {
        return worker(role + "-fixture", role, action);
    }
    AbstractBaseAgent worker(String id, String role, Function<Task, CompletableFuture<String>> action) {
        AbstractBaseAgent a = new AbstractBaseAgent(id, role, model) {
            @Override public CompletableFuture<String> processTask(Task task) { return action.apply(task); }
        }; register(a); return a;
    }
    @Test void cancelDuringPlanningReturnsImmediatelyButKeepsActualOwnership() throws Exception {
        CountDownLatch began = new CountDownLatch(1), finish = new CountDownLatch(1);
        PlannerAgent planner = new PlannerAgent(model) {
            @Override protected String callLlm(String s, String p) {
                began.countDown(); try { assertTrue(finish.await(5, TimeUnit.SECONDS)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return DAG;
            }
        }; register(planner); worker("research", t -> fail("must not start"));
        var handle = orchestrator.submit("goal", Map.of(), null, reports::add);
        assertTrue(began.await(3, TimeUnit.SECONDS));
        try {
            assertEquals("accepted", orchestrator.cancel(handle.executionId()).status());
            WorkflowOutcome outcome = handle.result().get(1, TimeUnit.SECONDS);
            assertEquals("cancelled", outcome.status()); assertEquals(TaskStatus.CANCELLED, outcome.planningTask().status());
            assertEquals(AgentState.BUSY, planner.getState());
            assertTrue(orchestrator.runStatus(handle.executionId()).runtime().cleanupPending());
            assertEquals("already_finished", orchestrator.cancel(handle.executionId()).status());
            assertEquals("not_found", orchestrator.cancel("missing").status());
            assertEquals(1, reports.stream().filter(r -> r.outcome() != null).count());
        } finally { finish.countDown(); }
    }
    @Test void parallelCancellationPreservesFinishedResultAndLateCleanupCannotRewriteOutcome() throws Exception {
        planner(DAG); CompletableFuture<String> a = new CompletableFuture<>(), b = new CompletableFuture<>();
        CountDownLatch started = new CountDownLatch(2);
        var research = worker("research", t -> { started.countDown(); return a; });
        worker("code", t -> { started.countDown(); return b; });
        worker("supervisor", t -> fail("C must not start"));
        var handle = orchestrator.submit("goal", Map.of(), null, reports::add);
        assertTrue(started.await(3, TimeUnit.SECONDS)); b.complete("finished B");
        orchestrator.cancel(handle.executionId());
        WorkflowOutcome outcome = handle.result().get(1, TimeUnit.SECONDS);
        assertEquals("cancelled", outcome.status());
        assertEquals("finished B", outcome.tasks().get(1).result());
        assertEquals(TaskStatus.CANCELLED, outcome.tasks().get(2).status());
        assertEquals(AgentState.BUSY, research.getState());
        a.complete("late A");
        assertEquals(AgentState.IDLE, research.getState());
        assertEquals(TaskStatus.CANCELLED, outcome.tasks().get(0).status());
        assertSame(outcome, handle.result().join());
        assertEquals(0, orchestrator.runStatus(handle.executionId()).runtime().activeTasks());
    }
    @Test void sharedDeadlineStopsNewWorkWithControlledClock() throws Exception {
        planner(DAG); CountDownLatch started = new CountDownLatch(2);
        CompletableFuture<String> actual = new CompletableFuture<>();
        worker("research", t -> { started.countDown(); return actual; }); worker("code", t -> { started.countDown(); return actual; });
        worker("supervisor", t -> fail("deadline must stop downstream"));
        var handle = orchestrator.submit("goal", Map.of(), 1000L, reports::add);
        assertTrue(started.await(3, TimeUnit.SECONDS)); clock.addAndGet(TimeUnit.SECONDS.toNanos(2));
        orchestrator.sweepRuns();
        WorkflowOutcome outcome = handle.result().get(1, TimeUnit.SECONDS);
        assertEquals("failed", outcome.status()); assertEquals("RUN_DEADLINE_EXCEEDED", outcome.error().code());
        assertTrue(outcome.tasks().stream().allMatch(t -> TaskLifecycle.terminal(t.status())));
        actual.complete("late"); assertSame(outcome, handle.result().join());
    }
    @Test void queuedCancelAndExpiredQueueNeverStartAfterCapacityReturns() throws Exception {
        orchestrator.shutdown(); settings.setConcurrentRuns(1); settings.setRunQueueCapacity(2);
        orchestrator = new OrchestratorAgent(model, settings, clock::get); orchestrator.init(bus);
        CountDownLatch began = new CountDownLatch(1), release = new CountDownLatch(1); AtomicInteger calls = new AtomicInteger();
        register(new PlannerAgent(model) { @Override protected String callLlm(String s, String p) {
            calls.incrementAndGet(); began.countDown(); try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return "invalid";
        }});
        var first = orchestrator.submit("first", Map.of(), null, r -> {}); assertTrue(began.await(3, TimeUnit.SECONDS));
        var cancelled = orchestrator.submit("second", Map.of(), null, reports::add);
        var expired = orchestrator.submit("third", Map.of(), 100L, reports::add);
        var rejected = orchestrator.submit("fourth", Map.of(), null, reports::add);
        assertEquals("AGENT_CAPACITY_EXCEEDED", rejected.result().get(1, TimeUnit.SECONDS).error().code());
        assertEquals("accepted", orchestrator.cancel(cancelled.executionId()).status());
        clock.addAndGet(TimeUnit.SECONDS.toNanos(1)); orchestrator.sweepRuns();
        assertEquals("RUN_DEADLINE_EXCEEDED", expired.result().get(1, TimeUnit.SECONDS).error().code());
        release.countDown(); first.result().get(3, TimeUnit.SECONDS);
        assertEquals(1, calls.get());
        assertTrue(reports.stream().anyMatch(r -> r.status().equals("queued")));
        assertEquals(0, orchestrator.runtimeMetrics().get("queuedRuns"));
    }
    @Test void cancelledWorkerQueueReleasesUnstartedPlannerWhileOtherRunRemainsBusy() throws Exception {
        orchestrator.shutdown(); settings.setWorkerThreads(1);
        orchestrator = new OrchestratorAgent(model, settings, clock::get); orchestrator.init(bus);
        planner("[{\"task_description\":\"A\",\"dependencies\":[],\"required_capability\":\"research\"}]");
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        var research = new ResearchAgent(model) { @Override protected String callLlm(String s, String p) {
            started.countDown(); try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return "A";
        }}; register(research);
        var first = orchestrator.submit("first", Map.of(), null, r -> {}); assertTrue(started.await(3, TimeUnit.SECONDS));
        CountDownLatch secondQueued = new CountDownLatch(1);
        var second = orchestrator.submit("second", Map.of(), null, r -> { if (r.planningTask().status() == TaskStatus.IN_PROGRESS) secondQueued.countDown(); });
        assertTrue(secondQueued.await(3, TimeUnit.SECONDS));
        try {
            orchestrator.cancel(second.executionId());
            assertEquals(0, orchestrator.runStatus(second.executionId()).runtime().activeTasks());
            assertEquals(0, orchestrator.runtimeMetrics().get("queuedWorkers"));
            assertFalse(first.result().isDone()); assertEquals(AgentState.BUSY, research.getState());
        } finally { release.countDown(); }
        assertTrue(first.result().get(3, TimeUnit.SECONDS).successful());
    }
    @Test void earlierBusinessFailureSurvivesLaterSiblingTimeout() throws Exception {
        settings.setTaskTimeoutMs(1000); planner(DAG);
        CountDownLatch started = new CountDownLatch(2), businessFailureRecorded = new CountDownLatch(1);
        CompletableFuture<String> pending = new CompletableFuture<>();
        worker("research", t -> { started.countDown(); return pending; });
        AbstractBaseAgent code = new AbstractBaseAgent("code-fixture", "code", model) {
            @Override public CompletableFuture<String> processTask(Task task) {
                started.countDown();
                return CompletableFuture.failedFuture(new WorkflowException("BUSINESS_FAILURE", "rules", "B failed first"));
            }
            @Override protected void publishSafely(AgentEvent event) {
                super.publishSafely(event);
                if (event.getType() == EventType.TASK_FAILED) businessFailureRecorded.countDown();
            }
        };
        register(code);
        worker("supervisor", t -> fail("must not start"));
        var handle = orchestrator.submit("goal", Map.of(), null, r -> {});
        assertTrue(started.await(3, TimeUnit.SECONDS));
        assertTrue(businessFailureRecorded.await(3, TimeUnit.SECONDS));
        clock.addAndGet(TimeUnit.SECONDS.toNanos(2));
        WorkflowOutcome outcome = handle.result().get(3, TimeUnit.SECONDS);
        assertEquals("BUSINESS_FAILURE", outcome.error().code());
        pending.complete("late");
    }

    @Test void cancellationOfOneRunDoesNotStopAnotherRun() throws Exception {
        String oneTask = "[{\"task_description\":\"A\",\"dependencies\":[],\"required_capability\":\"research\"}]";
        planner(oneTask);
        CompletableFuture<String> firstWork = new CompletableFuture<>(), secondWork = new CompletableFuture<>();
        CountDownLatch bothStarted = new CountDownLatch(2);
        Function<Task, CompletableFuture<String>> workByGoal = task -> {
            bothStarted.countDown();
            TaskExecutionContext context = (TaskExecutionContext) task.getInput().get("executionContext");
            return context.originalGoal().equals("run A") ? firstWork : secondWork;
        };
        worker("research-a", "research", workByGoal);
        worker("research-b", "research", workByGoal);

        var first = orchestrator.submit("run A", Map.of(), null, report -> {});
        var second = orchestrator.submit("run B", Map.of(), null, report -> {});
        assertTrue(bothStarted.await(3, TimeUnit.SECONDS));

        assertEquals("accepted", orchestrator.cancel(first.executionId()).status());
        assertEquals("cancelled", first.result().get(1, TimeUnit.SECONDS).status());
        assertFalse(second.result().isDone());
        secondWork.complete("run B completed");
        assertEquals("run B completed", second.result().get(2, TimeUnit.SECONDS).result());

        firstWork.complete("late result from cancelled run A");
        assertEquals("cancelled", first.result().join().status());
    }

    @Test void cancellingCompletedRunKeepsItsOriginalOutcome() throws Exception {
        planner("[{\"task_description\":\"A\",\"dependencies\":[],\"required_capability\":\"research\"}]");
        worker("research", task -> CompletableFuture.completedFuture("already complete"));
        var handle = orchestrator.submit("complete then cancel", Map.of(), null, report -> {});
        WorkflowOutcome completed = handle.result().get(2, TimeUnit.SECONDS);

        assertTrue(completed.successful());
        assertEquals("already_finished", orchestrator.cancel(handle.executionId()).status());
        assertSame(completed, orchestrator.runStatus(handle.executionId()).report().outcome());
        assertEquals("already complete", orchestrator.runStatus(handle.executionId()).report().outcome().result());
    }
}
