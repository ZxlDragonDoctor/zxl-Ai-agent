package com.zxl.zxlaiagent.multiagent;

import com.zxl.zxlaiagent.multiagent.runtime.*;
import com.zxl.zxlaiagent.multiagent.execution.*;
import com.zxl.zxlaiagent.multiagent.model.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeCapacityTest {
    @Test void boundedRunQueueRejectsAndCanRemoveCancelledWork() throws Exception {
        RuntimeSettings settings = new RuntimeSettings(); settings.setConcurrentRuns(1); settings.setRunQueueCapacity(1);
        try (ExecutionRuntime runtime = new ExecutionRuntime(settings)) {
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            runtime.dispatch(() -> { entered.countDown(); try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } });
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            AtomicInteger calls = new AtomicInteger(); Runnable queued = calls::incrementAndGet;
            runtime.dispatch(queued);
            assertThrows(WorkflowException.class, () -> runtime.dispatch(calls::incrementAndGet));
            assertTrue(runtime.removeQueued(queued)); release.countDown();
            runtime.close();
            assertTrue(runtime.awaitTermination(3, TimeUnit.SECONDS)); assertEquals(0, calls.get());
        }
    }
    @Test void registryDoesNotEvictAStoppedRunThatStillOwnsActualWork() {
        RuntimeSettings settings = new RuntimeSettings(); settings.setConcurrentRuns(1); settings.setRunQueueCapacity(1); settings.setRegistryCapacity(2);
        AtomicLong time = new AtomicLong(1); RunRegistry registry = new RunRegistry(settings, time::get);
        WorkflowRun run = new WorkflowRun("a", r -> {});
        var entry = registry.register(run, new ExecutionControl(1, Duration.ofSeconds(1), System::nanoTime));
        ExecutionLease actual = entry.taskLease(); run.finishFailure(new WorkflowError("STOP", "runtime", "stop"));
        WorkflowRun second = new WorkflowRun("b", r -> {}); registry.register(second, new ExecutionControl(1, Duration.ofSeconds(1), System::nanoTime));
        time.set(run.outcome().finishedAt() + settings.getRetentionMs() + 100);
        assertSame(entry, registry.find(run.id));
        assertTrue(registry.view(run.id).runtime().cleanupPending());
        actual.close(); actual.close();
        assertEquals(0, entry.activeTasks());
        assertNull(registry.find(run.id));
    }
}
