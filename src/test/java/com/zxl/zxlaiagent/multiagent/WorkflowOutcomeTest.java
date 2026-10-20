package com.zxl.zxlaiagent.multiagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zxl.zxlaiagent.multiagent.agents.*;
import com.zxl.zxlaiagent.multiagent.bus.AgentMessageBus;
import com.zxl.zxlaiagent.multiagent.execution.*;
import com.zxl.zxlaiagent.multiagent.model.*;
import com.zxl.zxlaiagent.multiagent.orchestration.OrchestratorAgent;
import com.zxl.zxlaiagent.controller.MultiAgentController;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkflowOutcomeTest {
    private final ChatModel model = mock(ChatModel.class);
    private final AgentMessageBus bus = mock(AgentMessageBus.class);
    private final OrchestratorAgent orchestrator = new OrchestratorAgent(model);
    private final List<WorkflowRun.Report> reports = new CopyOnWriteArrayList<>();
    private static final String THREE = """
            [{"task_description":"A","dependencies":[],"required_capability":"research"},
             {"task_description":"B","dependencies":[],"required_capability":"code"},
             {"task_description":"C","dependencies":[0,1],"required_capability":"supervisor"}]
            """;
    @BeforeEach void init() { orchestrator.init(bus); }
    @AfterEach void cleanup() { orchestrator.shutdown(); }
    private void exportReport(String name) {
        if (!Boolean.getBoolean("lesson6.exportReports")) return;
        try {
            var directory = java.nio.file.Path.of("target", "lesson6-ui");
            java.nio.file.Files.createDirectories(directory);
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(directory.resolve(name + ".json").toFile(), reports.get(reports.size() - 1));
        } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
    }
    private void planner(String json) {
        PlannerAgent planner = new PlannerAgent(model) {
            @Override protected String callLlm(String system, String prompt) { return json; }
        };
        planner.attachMessageBus(bus);
        orchestrator.registerAgent(planner);
    }
    private AbstractBaseAgent worker(String type, Function<Task, CompletableFuture<String>> work) {
        AbstractBaseAgent agent = new AbstractBaseAgent(type + "-fixture", type, model) {
            @Override public CompletableFuture<String> processTask(Task task) { return work.apply(task); }
        };
        agent.attachMessageBus(bus);
        orchestrator.registerAgent(agent);
        return agent;
    }
    @Test void failedBranchBlocksDependentsWithRootCauseAndWaitsForStartedSibling() throws Exception {
        planner("""
                [{"task_description":"A","dependencies":[],"required_capability":"research"},
                 {"task_description":"B","dependencies":[],"required_capability":"code"},
                 {"task_description":"D","dependencies":[3],"required_capability":"supervisor"},
                 {"task_description":"C","dependencies":[0,1],"required_capability":"supervisor"},
                 {"task_description":"E","dependencies":[],"required_capability":"research"}]
                """);
        CompletableFuture<String> a = new CompletableFuture<>(), b = new CompletableFuture<>();
        CountDownLatch started = new CountDownLatch(2);
        worker("research", task -> { started.countDown(); return a; });
        worker("code", task -> { started.countDown(); return b; });
        AtomicInteger downstreamCalls = new AtomicInteger();
        worker("supervisor", task -> { downstreamCalls.incrementAndGet(); return CompletableFuture.completedFuture("must not run"); });
        CompletableFuture<WorkflowOutcome> waiting = CompletableFuture.supplyAsync(() -> orchestrator.run("goal", Map.of(), reports::add));
        assertTrue(started.await(5, TimeUnit.SECONDS));
        a.completeExceptionally(new WorkflowException("FACT_FAILURE", "facts", "controlled failure"));
        assertFalse(waiting.isDone());
        b.complete("real rules");
        WorkflowOutcome outcome = waiting.get(5, TimeUnit.SECONDS);
        assertFalse(outcome.successful());
        Map<String, TaskExecutionView> tasks = new HashMap<>();
        outcome.tasks().forEach(task -> tasks.put(task.description(), task));
        assertEquals(TaskStatus.FAILED, tasks.get("A").status());
        assertEquals("real rules", tasks.get("B").result());
        assertEquals(TaskStatus.COMPLETED, tasks.get("B").status());
        assertEquals("DEPENDENCY_FAILED", tasks.get("C").failure().reason());
        assertEquals(List.of(tasks.get("A").id()), tasks.get("C").failure().blockedByTaskIds());
        assertEquals(tasks.get("A").id(), tasks.get("D").failure().sourceTaskId());
        assertEquals(List.of(tasks.get("C").id()), tasks.get("D").failure().blockedByTaskIds());
        assertEquals("PLAN_ABORTED", tasks.get("E").failure().reason());
        assertEquals(0, downstreamCalls.get());
        assertEquals(tasks.get("A").id(), outcome.error().sourceTaskId());
        assertSame(outcome, reports.get(reports.size() - 1).outcome());
        assertEquals(1, reports.stream().filter(report -> report.outcome() != null).count());
        verify(bus, times(1)).publishEvent(argThat(event -> event.getType() == EventType.TASK_FAILED));
        exportReport("branch-failure");
    }
    @Test void successfulDagUsesActualInputsAndPreservesOriginalMaterial() {
        planner(THREE);
        worker("research", task -> CompletableFuture.completedFuture("ACTUAL_FACTS"));
        worker("code", task -> CompletableFuture.completedFuture("ACTUAL_RULES"));
        worker("supervisor", task -> {
            TaskExecutionContext context = (TaskExecutionContext) task.getInput().get("executionContext");
            assertEquals("goal", context.originalGoal());
            assertEquals("P2", context.userInput().get("policy"));
            assertEquals(List.of("ACTUAL_FACTS", "ACTUAL_RULES"), context.dependencyResults().stream().map(TaskExecutionContext.DependencyResult::output).toList());
            assertThrows(UnsupportedOperationException.class, () -> context.userInput().put("policy", "changed"));
            return CompletableFuture.completedFuture("draft");
        });
        WorkflowOutcome outcome = orchestrator.run("goal", Map.of("policy", "P2"), reports::add);
        assertTrue(outcome.successful());
        assertEquals("draft", outcome.result());
        assertEquals(3, outcome.tasks().size());
        assertNull(outcome.synthesisTask());
        assertEquals(1, outcome.planningModelCalls());
        exportReport("success");
    }
    @Test void extraSynthesisReceivesOriginalMaterialAndFailureIsNotHidden() {
        planner(THREE.substring(0, THREE.indexOf(",\n {\"task_description\":\"C\"")) + "]");
        worker("research", task -> CompletableFuture.completedFuture("facts"));
        worker("code", task -> CompletableFuture.completedFuture("rules"));
        worker("supervisor", task -> {
            var context = (TaskExecutionContext) task.getInput().get("executionContext");
            assertEquals("P2", context.userInput().get("policy"));
            assertEquals(2, context.dependencyResults().size());
            return CompletableFuture.failedFuture(new WorkflowException("REVIEW_FAILED", "review", "controlled review failure"));
        });
        WorkflowOutcome outcome = orchestrator.run("goal", Map.of("policy", "P2"), reports::add);
        assertFalse(outcome.successful());
        assertTrue(outcome.tasks().stream().allMatch(task -> task.status() == TaskStatus.COMPLETED));
        assertEquals(TaskStatus.FAILED, outcome.synthesisTask().status());
        assertEquals("REVIEW_FAILED", outcome.error().code());
        assertEquals("synthesis", outcome.error().path());
        assertTrue(reports.stream().anyMatch(report -> report.status().equals("synthesizing")));
        exportReport("synthesis-failure");
    }
    @Test void planningFailureHasOutcomeWithoutPlanOrWorkerCalls() {
        planner("invalid JSON");
        AtomicInteger calls = new AtomicInteger();
        worker("research", task -> { calls.incrementAndGet(); return CompletableFuture.completedFuture("unexpected"); });
        WorkflowOutcome outcome = orchestrator.run("goal", Map.of(), reports::add);
        assertFalse(outcome.successful());
        assertNull(outcome.planId());
        assertEquals(TaskStatus.FAILED, outcome.planningTask().status());
        assertTrue(outcome.tasks().isEmpty());
        assertEquals(0, calls.get());
        exportReport("planning-failure");
    }
    @Test void terminalReportIsFrozenAndSubscriberExceptionsDoNotAlterSuccess() {
        WorkflowRun run = new WorkflowRun("goal", report -> { reports.add(report); throw new IllegalStateException("subscriber"); });
        TaskLifecycle.start(run.planningTask, "planner"); TaskLifecycle.complete(run.planningTask, "valid plan");
        Plan plan = Plan.create("goal");
        Task task = Task.create("single"); task.setInput(Map.of("capability", "research")); plan.addTask(task); run.plan = plan;
        TaskLifecycle.start(task, "research"); TaskLifecycle.complete(task, "done");
        WorkflowOutcome outcome = run.finishSuccess("done");
        assertTrue(outcome.successful());
        run.emit("running", null, null);
        assertSame(outcome, run.finishFailure(new WorkflowError("LATE", "workflow", "late")));
        assertEquals(1, reports.size());
        assertThrows(UnsupportedOperationException.class, () -> outcome.tasks().clear());
        assertEquals("done", reports.get(0).result());
    }
    @Test void orchestratorTimeoutAndLateWorkerCompletionKeepOneFailureOutcome() throws Exception {
        planner("[{\"task_description\":\"A\",\"dependencies\":[],\"required_capability\":\"research\"}]");
        CompletableFuture<String> underlying = new CompletableFuture<>();
        AbstractBaseAgent worker = new AbstractBaseAgent("timeout-worker", "research", model) {
            @Override public CompletableFuture<String> processTask(Task task) { return underlying; }
            @Override public CompletableFuture<String> executeTask(Task task) {
                CompletableFuture<String> actual = super.executeTask(task);
                CompletableFuture<String> wait = new CompletableFuture<>() {
                    @Override public String get(long timeout, TimeUnit unit) throws TimeoutException {
                        throw new TimeoutException("controlled waiting deadline");
                    }
                };
                actual.whenComplete((output, error) -> { if (error == null) wait.complete(output); else wait.completeExceptionally(error); });
                return wait;
            }
        };
        worker.attachMessageBus(bus); orchestrator.registerAgent(worker);
        WorkflowOutcome outcome = orchestrator.run("goal", Map.of(), reports::add);
        assertFalse(outcome.successful());
        assertEquals("TASK_TIMEOUT", outcome.error().code());
        assertEquals(TaskStatus.FAILED, outcome.tasks().get(0).status());
        assertEquals(AgentState.BUSY, worker.getState());
        assertEquals(1, orchestrator.getStatus().get("pendingTasks"));
        int reportCount = reports.size();
        underlying.complete("late output");
        assertEquals(AgentState.IDLE, worker.getState());
        assertEquals(0, orchestrator.getStatus().get("pendingTasks"));
        assertEquals(reportCount, reports.size());
        assertEquals(TaskStatus.FAILED, outcome.tasks().get(0).status());
        assertNull(outcome.result());
        verify(bus, times(1)).publishEvent(argThat(event -> event.getType() == EventType.TASK_FAILED));
    }
    @Test void successfulWaitAloneCannotCreateSuccessfulOutcome() {
        WorkflowRun run = new WorkflowRun("goal", reports::add);
        run.plan = Plan.create("goal");
        Task task = Task.create("unfinished"); task.setInput(Map.of("capability", "research")); run.plan.addTask(task);
        WorkflowOutcome outcome = run.finishSuccess("invented final output");
        assertFalse(outcome.successful());
        assertEquals("PLAN_INCOMPLETE", outcome.error().code());
        assertNull(outcome.result());
    }
    @Test void httpResponseUsesTheSameAuthoritativeOutcome() {
        OrchestratorAgent mock = mock(OrchestratorAgent.class);
        WorkflowRun run = new WorkflowRun("goal", report -> {});
        WorkflowOutcome outcome = run.finishFailure(new WorkflowError("CONTROLLED", "planning", "controlled"));
        when(mock.run(eq("goal"), anyMap(), any())).thenReturn(outcome);
        MultiAgentController controller = new MultiAgentController();
        ReflectionTestUtils.setField(controller, "orchestratorAgent", mock);
        Map<String, Object> response = controller.orchestrate(new MultiAgentController.OrchestrationRequest("goal", Map.of()));
        assertSame(outcome, response.get("outcome"));
        assertEquals(false, response.get("success"));
        assertEquals(outcome.error().code(), response.get("code"));
        assertEquals(outcome.error().path(), response.get("path"));
    }
    @Test void reversedDependencyOrderKeepsTheSelectedRootCause() {
        Plan plan = Plan.create("goal");
        Task a = Task.create("A"), b = Task.create("B");
        Task c = Task.create("C", List.of(b.getId(), a.getId()));
        Task d = Task.create("D", List.of(c.getId()));
        plan.setTasks(List.of(a, b, c, d));
        TaskLifecycle.start(a, "research"); TaskLifecycle.start(b, "code");
        WorkflowError first = new WorkflowError("FIRST", "facts", "first failure").forTask(a.getId());
        TaskLifecycle.fail(a, first); TaskLifecycle.fail(b, new WorkflowError("SECOND", "rules", "second failure"));
        FailurePropagation.blockPending(plan, first);
        assertEquals(a.getId(), c.snapshot().failure().sourceTaskId());
        assertEquals(a.getId(), d.snapshot().failure().sourceTaskId());
        assertEquals(List.of(b.getId(), a.getId()), c.snapshot().failure().blockedByTaskIds());
    }
    @Test void unavailableFinalSupervisorHasTerminalBlockedSynthesis() {
        planner("[{\"task_description\":\"A\",\"dependencies\":[],\"required_capability\":\"research\"},"
                + "{\"task_description\":\"B\",\"dependencies\":[],\"required_capability\":\"code\"}]");
        worker("research", task -> CompletableFuture.completedFuture("facts"));
        worker("code", task -> CompletableFuture.completedFuture("rules"));
        AgentInterface unavailable = mock(AgentInterface.class);
        when(unavailable.getAgentId()).thenReturn("busy-supervisor");
        when(unavailable.getAgentType()).thenReturn("supervisor");
        when(unavailable.getState()).thenReturn(AgentState.BUSY);
        orchestrator.registerAgent(unavailable);
        WorkflowOutcome outcome = orchestrator.run("goal", Map.of(), reports::add);
        assertFalse(outcome.successful());
        assertTrue(outcome.tasks().stream().allMatch(task -> task.status() == TaskStatus.COMPLETED));
        assertEquals("AGENT_BUSY", outcome.error().code());
        assertEquals("synthesis", outcome.error().path());
        assertEquals(TaskStatus.BLOCKED, outcome.synthesisTask().status());
        assertTrue(outcome.synthesisTask().completedAt() > 0);
        assertEquals(0, outcome.synthesisModelCalls());
    }
    @Test void missingPlannerDoesNotLeavePendingPlanningTaskInTerminalOutcome() {
        WorkflowOutcome outcome = orchestrator.run("goal", Map.of(), reports::add);
        assertFalse(outcome.successful());
        assertNull(outcome.planId());
        assertEquals("AGENT_TYPE_NOT_REGISTERED", outcome.error().code());
        assertEquals(TaskStatus.BLOCKED, outcome.planningTask().status());
        assertTrue(outcome.planningTask().completedAt() > 0);
    }
}
