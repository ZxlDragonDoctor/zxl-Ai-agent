package com.zxl.zxlaiagent.multiagent;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zxl.zxlaiagent.multiagent.model.*;
import com.zxl.zxlaiagent.multiagent.planning.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class TaskLifecycleTest {
    @Test void illegalTransitionsAndLateCallbacksPreserveTheSameSnapshot() {
        Task task = Task.create("lifecycle");
        assertFalse(TaskLifecycle.complete(task, "premature").applied());
        assertFalse(TaskLifecycle.fail(task, new WorkflowError("FAIL", "task", "failure")).applied());
        assertTrue(TaskLifecycle.start(task, "worker").applied());
        assertTrue(TaskLifecycle.recordModelCall(task));
        assertTrue(TaskLifecycle.complete(task, "real output").applied());
        TaskSnapshot terminal = task.snapshot();
        assertEquals(1, terminal.modelCalls());
        assertEquals("real output", terminal.result());
        assertTrue(terminal.startedAt() > 0 && terminal.completedAt() >= terminal.startedAt());
        assertFalse(TaskLifecycle.complete(task, "duplicate").applied());
        assertFalse(TaskLifecycle.fail(task, new WorkflowError("FAIL", "task", "late")).applied());
        assertFalse(TaskLifecycle.start(task, "other").applied());
        assertFalse(TaskLifecycle.recordModelCall(task));
        assertFalse(TaskLifecycle.cancel(task, new WorkflowError("CANCELLED", "task", "late")).applied());
        assertSame(terminal, task.snapshot());
    }
    @Test void concurrentSuccessAndFailureHaveExactlyOneWinner() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 20; i++) {
                Task task = Task.create("race");
                TaskLifecycle.start(task, "worker");
                CountDownLatch start = new CountDownLatch(1);
                Future<Boolean> success = executor.submit(() -> { start.await(); return TaskLifecycle.complete(task, "output").applied(); });
                Future<Boolean> failure = executor.submit(() -> { start.await(); return TaskLifecycle.fail(task, new WorkflowError("FAIL", "task", "controlled")).applied(); });
                start.countDown();
                assertNotEquals(success.get(5, TimeUnit.SECONDS), failure.get(5, TimeUnit.SECONDS));
                TaskSnapshot state = task.snapshot();
                if (state.status() == TaskStatus.COMPLETED) { assertEquals("output", state.result()); assertNull(state.failure()); }
                else { assertEquals(TaskStatus.FAILED, state.status()); assertNull(state.result()); assertNotNull(state.failure()); }
            }
        } finally { executor.shutdownNow(); }
    }
    @Test void pendingBlockAndCancelAreTerminalAndKeepTheirReasons() {
        Task task = Task.create("blocked");
        WorkflowError cause = new WorkflowError("DEPENDENCY_FAILED", "task", "upstream failed", "A", List.of("B"), "DEPENDENCY_FAILED");
        assertTrue(TaskLifecycle.block(task, cause).applied());
        assertFalse(TaskLifecycle.start(task, "worker").applied());
        assertEquals("A", task.snapshot().failure().sourceTaskId());
        Task cancelled = Task.create("cancelled");
        assertTrue(TaskLifecycle.cancel(cancelled, new WorkflowError("CANCELLED", "task", "cancelled")).applied());
        assertFalse(TaskLifecycle.complete(cancelled, "late").applied());
        assertEquals(TaskStatus.CANCELLED, cancelled.getStatus());
    }
    @Test void planWireRoundTripAndForgedSnapshotsAreChecked() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Plan plan = new PlanParser(mapper, new PlanValidator()).parse("[{\"task_description\":\"A\",\"dependencies\":[],\"required_capability\":\"research\"}]", "goal");
        String json = mapper.writeValueAsString(plan);
        Plan restored = mapper.readValue(json, Plan.class);
        new PlanValidator().validate(restored);
        ObjectNode tree = (ObjectNode) mapper.readTree(json);
        ((ObjectNode) tree.path("tasks").get(0).path("execution")).put("status", "COMPLETED").put("result", "forged");
        Plan forged = mapper.treeToValue(tree, Plan.class);
        assertThrows(PlanValidationException.class, () -> new PlanValidator().validate(forged));
        ObjectNode flat = (ObjectNode) mapper.readTree(json);
        ((ObjectNode) flat.path("tasks").get(0)).put("status", "COMPLETED");
        assertThrows(Exception.class, () -> mapper.readerFor(Plan.class).with(DeserializationFeature.FAIL_ON_IGNORED_PROPERTIES).readValue(flat.toString()));
    }
}
