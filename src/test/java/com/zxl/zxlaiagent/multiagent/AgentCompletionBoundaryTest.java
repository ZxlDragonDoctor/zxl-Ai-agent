package com.zxl.zxlaiagent.multiagent;

import com.zxl.zxlaiagent.multiagent.agents.AbstractBaseAgent;
import com.zxl.zxlaiagent.multiagent.bus.AgentMessageBus;
import com.zxl.zxlaiagent.multiagent.model.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import java.util.concurrent.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AgentCompletionBoundaryTest {
    private AbstractBaseAgent worker(AgentMessageBus bus, Function<Task, CompletableFuture<String>> body) {
        AbstractBaseAgent agent = new AbstractBaseAgent("worker", "research", mock(ChatModel.class)) {
            @Override public CompletableFuture<String> processTask(Task task) { return body.apply(task); }
        };
        agent.attachMessageBus(bus);
        return agent;
    }
    @Test void nullFutureFailsAndReleasesWorker() {
        var agent = worker(mock(AgentMessageBus.class), task -> null);
        Task task = Task.create("null future");
        CompletableFuture<String> result = assertDoesNotThrow(() -> agent.executeTask(task));
        assertThrows(CompletionException.class, result::join);
        assertEquals(TaskStatus.FAILED, task.getStatus());
        assertEquals(AgentState.IDLE, agent.getState());
    }
    @Test void blankOutputHasAnActualFailedTask() {
        var agent = worker(mock(AgentMessageBus.class), task -> CompletableFuture.completedFuture(" "));
        Task task = Task.create("blank");
        assertThrows(CompletionException.class, () -> agent.executeTask(task).join());
        assertEquals(TaskStatus.FAILED, task.getStatus());
        assertNotNull(task.getErrorCode());
        assertTrue(task.getCompletedAt() > 0);
        assertEquals(AgentState.IDLE, agent.getState());
    }
    @Test void observerFailureCannotLeakOwnershipOrChangeBusinessSuccess() {
        AgentMessageBus bus = mock(AgentMessageBus.class);
        doThrow(new IllegalStateException("observer broken")).when(bus).publishEvent(any());
        var agent = worker(bus, task -> CompletableFuture.completedFuture("actual output"));
        Task task = Task.create("safe observer");
        assertEquals("actual output", assertDoesNotThrow(() -> agent.executeTask(task).join()));
        assertEquals(TaskStatus.COMPLETED, task.getStatus());
        assertEquals(AgentState.IDLE, agent.getState());
    }
    @Test void lateOutputCannotOverwriteFailedTask() {
        CompletableFuture<String> underlying = new CompletableFuture<>();
        var agent = worker(mock(AgentMessageBus.class), task -> underlying);
        Task task = Task.create("late");
        CompletableFuture<String> result = agent.executeTask(task);
        TaskLifecycle.fail(task, new WorkflowError("TASK_TIMEOUT", "task", "timeout"));
        assertEquals(AgentState.BUSY, agent.getState());
        underlying.complete("too late");
        assertThrows(CompletionException.class, result::join);
        assertEquals(TaskStatus.FAILED, task.getStatus());
        assertNull(task.getResult());
        assertEquals("TASK_TIMEOUT", task.getErrorCode());
        assertEquals(AgentState.IDLE, agent.getState());
    }
    @Test void shutdownCannotBeReopenedByCompletion() {
        CompletableFuture<String> underlying = new CompletableFuture<>();
        var agent = worker(mock(AgentMessageBus.class), task -> underlying);
        Task task = Task.create("shutdown");
        var result = agent.executeTask(task);
        agent.shutdown();
        underlying.complete("done");
        assertEquals("done", result.join());
        assertEquals(AgentState.SHUTDOWN, agent.getState());
    }
    @Test void synchronousExceptionAndAsyncFailureShareCleanup() {
        for (boolean synchronous : new boolean[]{true, false}) {
            var agent = worker(mock(AgentMessageBus.class), task -> {
                var error = new WorkflowException("CONTROLLED_FAILURE", "task", "controlled");
                if (synchronous) throw error;
                return CompletableFuture.failedFuture(error);
            });
            Task task = Task.create("failure");
            assertThrows(CompletionException.class, () -> agent.executeTask(task).join());
            assertEquals("CONTROLLED_FAILURE", task.getErrorCode());
            assertEquals(TaskStatus.FAILED, task.getStatus());
            assertEquals(AgentState.IDLE, agent.getState());
        }
    }
    @Test void cancellingWaitingFutureDoesNotDetachUnderlyingCleanup() {
        AgentMessageBus bus = mock(AgentMessageBus.class);
        CompletableFuture<String> underlying = new CompletableFuture<>();
        var agent = worker(bus, task -> underlying);
        Task task = Task.create("disconnect");
        CompletableFuture<String> waiting = agent.executeTask(task);
        assertTrue(waiting.cancel(true));
        assertEquals(AgentState.BUSY, agent.getState());
        underlying.complete("actual output");
        assertEquals(TaskStatus.COMPLETED, task.getStatus());
        assertEquals(AgentState.IDLE, agent.getState());
        assertThrows(CompletionException.class, () -> agent.executeTask(task).join());
        verify(bus, times(1)).publishEvent(argThat(event -> event.getType() == EventType.TASK_COMPLETED));
    }
    @Test void failureWhilePreparingStartedEventStillReleasesOwnership() {
        var agent = worker(mock(AgentMessageBus.class), task -> CompletableFuture.completedFuture("unused"));
        Task task = Task.create(null);
        CompletableFuture<String> waiting = assertDoesNotThrow(() -> agent.executeTask(task));
        assertThrows(CompletionException.class, waiting::join);
        assertEquals(TaskStatus.FAILED, task.getStatus());
        assertEquals(AgentState.IDLE, agent.getState());
    }
}
