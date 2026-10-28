package com.zxl.zxlaiagent.agent;

import com.zxl.zxlaiagent.agent.model.AgentEndReason;
import com.zxl.zxlaiagent.agent.model.AgentExecutionEvent;
import com.zxl.zxlaiagent.security.Phase;
import com.zxl.zxlaiagent.security.execution.OperationBoundary;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

class AgentPublicationBoundaryTest {
    static final class Boundary implements OperationBoundary {
        volatile boolean allowed = true;
        final CountDownLatch admitted = new CountDownLatch(1);
        public synchronized void check(Phase phase) { if (!allowed) throw new SecurityException("revoked"); }
        public synchronized <T> T start(Phase phase, Supplier<T> operation) {
            check(phase); T result = operation.get(); admitted.countDown(); return result;
        }
        synchronized void revoke() { allowed = false; }
    }
    static final class PublishingAgent extends BaseAgent {
        public String step() { return "synthetic"; }
        void publishSecret() { emitExecutionEvent(AgentExecutionEvent.of("ANSWER_DELTA", 1, "secret", "running")); }
    }

    @Test void identityBindsOnceAndCannotChangeAfterSynchronousClaim() {
        var agent = new PublishingAgent();
        agent.bindOperationBoundary(new Boundary());
        assertThrows(IllegalStateException.class, () -> agent.bindOperationBoundary(new Boundary()));
        agent.setMaxSteps(1);
        agent.run("synthetic");
        assertThrows(IllegalStateException.class, () -> agent.bindOperationBoundary(new Boundary()));
    }

    @Test void deniedPublicationNeverCapturesOrSends() {
        var agent = new PublishingAgent(); var boundary = new Boundary();
        agent.bindOperationBoundary(boundary); boundary.revoke();
        AtomicInteger observed = new AtomicInteger(); AtomicInteger sent = new AtomicInteger();
        agent.setEventObserver(event -> observed.incrementAndGet());
        agent.setExecutionListener(event -> sent.incrementAndGet());
        assertThrows(SecurityException.class, agent::publishSecret);
        assertEquals(0, observed.get()); assertEquals(0, sent.get());
    }

    @Test void revocationWhileQueuedRejectsBeforeObserverAndSend() throws Exception {
        var field = BaseAgent.class.getDeclaredField("PUBLICATIONS"); field.setAccessible(true);
        var executor = (ThreadPoolExecutor) field.get(null);
        assertEquals(32, executor.getQueue().remainingCapacity());
        CountDownLatch occupied = new CountDownLatch(4), release = new CountDownLatch(1);
        for (int i = 0; i < 4; i++) executor.execute(() -> {
            occupied.countDown(); try { release.await(); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        });
        assertTrue(occupied.await(1, TimeUnit.SECONDS));
        var agent = new PublishingAgent(); var boundary = new Boundary(); agent.bindOperationBoundary(boundary);
        AtomicInteger captures = new AtomicInteger(), sends = new AtomicInteger();
        agent.setEventObserver(event -> captures.incrementAndGet()); agent.setExecutionListener(event -> sends.incrementAndGet());
        var result = CompletableFuture.runAsync(agent::publishSecret);
        try {
            assertTrue(boundary.admitted.await(1, TimeUnit.SECONDS));
            assertEquals(1, executor.getQueue().size());
            boundary.revoke();
        } finally { release.countDown(); }
        assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
        assertEquals(0, captures.get()); assertEquals(0, sends.get());
    }

    @Test void fullPublicationQueueRejectsWithoutCapturingOrCreatingExtraWorkers() throws Exception {
        var field = BaseAgent.class.getDeclaredField("PUBLICATIONS"); field.setAccessible(true);
        var executor = (ThreadPoolExecutor) field.get(null);
        CountDownLatch occupied = new CountDownLatch(4), release = new CountDownLatch(1);
        Runnable blocker = () -> {
            occupied.countDown(); try { release.await(); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        };
        for (int i = 0; i < 4; i++) executor.execute(blocker);
        assertTrue(occupied.await(1, TimeUnit.SECONDS));
        try {
            for (int i = 0; i < 32; i++) executor.execute(blocker);
            assertEquals(0, executor.getQueue().remainingCapacity());
            var agent = new PublishingAgent(); agent.bindOperationBoundary(new Boundary());
            AtomicInteger captured = new AtomicInteger(); agent.setEventObserver(event -> captured.incrementAndGet());
            assertThrows(CancellationException.class, agent::publishSecret);
            assertTrue(agent.isStreamClosed()); assertEquals(0, captured.get());
            assertEquals(4, executor.getMaximumPoolSize()); assertEquals(32, executor.getQueue().size());
        } finally {
            executor.getQueue().clear(); release.countDown();
        }
    }

    @Test void slowConsumerDoesNotHoldAuthorizationLockAndProducerWaitIsBounded() throws Exception {
        var agent = new PublishingAgent(); var boundary = new Boundary(); agent.bindOperationBoundary(boundary);
        CountDownLatch sending = new CountDownLatch(1), release = new CountDownLatch(1);
        agent.setExecutionListener(event -> {
            sending.countDown();
            try { release.await(); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        });
        var result = CompletableFuture.runAsync(agent::publishSecret);
        try {
            assertTrue(sending.await(1, TimeUnit.SECONDS));
            CompletableFuture.runAsync(boundary::revoke).get(300, TimeUnit.MILLISECONDS);
            assertThrows(ExecutionException.class, () -> result.get(3, TimeUnit.SECONDS));
            assertTrue(agent.isStreamClosed());
        } finally { release.countDown(); }
    }

    @Test void revokeDuringStepDropsResultRawFrameAndTerminalButStillReleasesCompletion() throws Exception {
        var boundary = new Boundary(); CountDownLatch cleaned = new CountDownLatch(1);
        AtomicInteger completed = new AtomicInteger(), captured = new AtomicInteger();
        BaseAgent agent = new BaseAgent() {
            public String step() { boundary.revoke(); return "private-tool-result"; }
            protected void cleanup() { cleaned.countDown(); }
        };
        agent.bindOperationBoundary(boundary);
        agent.setEventObserver(event -> captured.incrementAndGet());
        agent.setCompletionListener(snapshot -> completed.incrementAndGet());
        var emitter = agent.prepareStream("synthetic");
        emitter.activate();
        assertTrue(cleaned.await(3, TimeUnit.SECONDS));
        assertEquals(1, completed.get()); assertEquals(2, captured.get());
        var frames = ResponseBodyEmitter.class.getDeclaredField("earlySendAttempts"); frames.setAccessible(true);
        var buffered = (java.util.Set<?>) frames.get(emitter);
        assertTrue(buffered.stream().map(frame -> (ResponseBodyEmitter.DataWithMediaType) frame)
                .noneMatch(frame -> frame.getData().toString().contains("private-tool-result")));
        var done = ResponseBodyEmitter.class.getDeclaredField("complete"); done.setAccessible(true);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (!(boolean) done.get(emitter) && System.nanoTime() < deadline) Thread.yield();
        assertEquals(true, done.get(emitter));
        assertThrows(IllegalStateException.class, () -> agent.bindOperationBoundary(new Boundary()));
    }
    @Test void rejectedTerminalStillCompletesEmitterAndCleansUp() throws Exception {
        CountDownLatch cleaned = new CountDownLatch(1);
        BaseAgent agent = new BaseAgent() {
            public String step() { finishRun(AgentEndReason.ANSWER_READY); return "secret"; }
            protected void cleanup() { cleaned.countDown(); }
        };
        agent.setEventObserver(event -> {
            if (event.type().startsWith("RUN_COMPLETED")) throw new SecurityException("revoked");
        });
        var emitter = agent.runStream("synthetic");
        assertTrue(cleaned.await(3, TimeUnit.SECONDS));
        var state = ResponseBodyEmitter.class.getDeclaredField("complete");
        state.setAccessible(true);
        assertEquals(true, state.get(emitter), "rejected terminal must close without sensitive output");
    }
}
