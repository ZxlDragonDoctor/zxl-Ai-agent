package com.zxl.zxlaiagent.multiagent;

import com.zxl.zxlaiagent.multiagent.runtime.*;
import com.zxl.zxlaiagent.multiagent.execution.WorkflowRun;
import com.zxl.zxlaiagent.multiagent.model.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeCapacityMetricsTest {
    @Test void coordinatorRejectionsRemainSeparateAndRemovalAllowsReadmission() throws Exception {
        RuntimeSettings settings = limits();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (ExecutionRuntime runtime = new ExecutionRuntime(settings)) {
            try {
                runtime.dispatch(() -> block(entered, release));
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                Runnable queued = () -> fail("removed run must not execute");
                runtime.dispatch(queued);
                assertEquals("AGENT_CAPACITY_EXCEEDED", assertThrows(WorkflowException.class, () -> runtime.dispatch(() -> {})).getCode());
                assertEquals(1, runtime.metrics().get("runRejections"));
                assertEquals(1, runtime.metrics().get("rejected"));
                assertEquals(0, runtime.metrics().get("workerRejections"));
                assertTrue(runtime.removeQueued(queued));
                CountDownLatch readmitted = new CountDownLatch(1);
                runtime.dispatch(readmitted::countDown);
                release.countDown();
                assertTrue(readmitted.await(3, TimeUnit.SECONDS));
                assertEquals(1, runtime.metrics().get("runRejections"));
            } finally { release.countDown(); }
        }
    }

    @Test void workerRejectionIsCountedButTimeoutSweepAndReadmissionAreNotRejections() throws Exception {
        workerScenario(false);
    }
    @Test void cancelledQueuedWorkerReleasesCapacityWithoutCountingARejection() throws Exception {
        workerScenario(true);
    }
    private void workerScenario(boolean cancel) throws Exception {
        RuntimeSettings settings = limits(); settings.setQueueTimeoutMs(100);
        AtomicLong clock = new AtomicLong(1);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (ExecutionRuntime runtime = new ExecutionRuntime(settings, clock::get)) {
            try {
                var running = runtime.async(Task.create("blocking"), () -> { block(entered, release); return "first"; });
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                var control = new ExecutionControl(1, Duration.ofSeconds(10), clock::get);
                var entry = new RunRegistry(settings).register(new WorkflowRun("queued", r -> {}), control);
                Task task = Task.create("queued"); runtime.bind(task, entry);
                var queued = runtime.async(task, () -> { fail("removed worker must not execute"); return "bad"; });
                assertEquals("AGENT_CAPACITY_EXCEEDED", assertThrows(WorkflowException.class,
                        () -> runtime.async(Task.create("rejected"), () -> "bad")).getCode());
                assertEquals(1, runtime.metrics().get("workerRejections"));
                assertEquals(0, runtime.metrics().get("runRejections"));
                assertEquals(0, runtime.metrics().get("rejected"));
                assertEquals(1, runtime.metrics().get("workerQueuePeak"));
                if (cancel) { assertTrue(control.cancel()); runtime.removeStoppedWork(entry); }
                else { clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(100)); runtime.sweepWorkerQueue(); }
                CompletionException failure = assertThrows(CompletionException.class, queued::join);
                assertEquals(cancel ? "RUN_CANCELLED" : "CAPACITY_QUEUE_TIMEOUT", ((WorkflowException) failure.getCause()).getCode());
                runtime.unbind(task);
                assertEquals(0, runtime.metrics().get("queuedWorkers"));
                var readmitted = runtime.async(Task.create("readmitted"), () -> "ok");
                release.countDown();
                assertEquals("first", running.get(3, TimeUnit.SECONDS));
                assertEquals("ok", readmitted.get(3, TimeUnit.SECONDS));
                assertEquals(1, runtime.metrics().get("workerRejections"));
                assertEquals(1, runtime.metrics().get("workerQueuePeak"));
            } finally { release.countDown(); }
        }
    }
    private static RuntimeSettings limits() {
        RuntimeSettings s = new RuntimeSettings(); s.setConcurrentRuns(1); s.setRunQueueCapacity(1);
        s.setWorkerThreads(1); s.setWorkerQueueCapacity(1); return s;
    }
    private static void block(CountDownLatch entered, CountDownLatch release) {
        entered.countDown();
        try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("test release timeout"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
}
