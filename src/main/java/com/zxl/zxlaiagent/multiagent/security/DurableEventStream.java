package com.zxl.zxlaiagent.multiagent.security;

import com.zxl.zxlaiagent.agent.stream.PreparedAgentStream;
import com.zxl.zxlaiagent.security.ActorRef;
import com.zxl.zxlaiagent.security.SecurityFailure;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** A bounded subscription over committed events. Closing it has no workflow side effects. */
final class DurableEventStream {
    private static final int CAPACITY = 8;
    private static final Semaphore SLOTS = new Semaphore(CAPACITY);
    private static final ThreadPoolExecutor WORKERS = new ThreadPoolExecutor(CAPACITY, CAPACITY, 30,
            TimeUnit.SECONDS, new ArrayBlockingQueue<>(CAPACITY), job -> {
        Thread worker = new Thread(job, "durable-run-sse");
        worker.setDaemon(true);
        return worker;
    });

    private DurableEventStream() {}

    static PreparedAgentStream prepare(ScopedMultiAgentService service, ActorRef actor, String id, long cursor) {
        if (!SLOTS.tryAcquire()) throw new SecurityFailure("RUN_STREAM_CAPACITY", 429);
        var stopped = new AtomicBoolean();
        var emitterRef = new AtomicReference<PreparedAgentStream>();
        var emitter = new PreparedAgentStream(25_000, () -> WORKERS.execute(() -> {
            var stream = emitterRef.get();
            try { replay(stream, service, actor, id, cursor, stopped); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch (IOException | RuntimeException closed) {
                // The client reconnects from the last event ID that actually arrived.
            } finally {
                SLOTS.release();
                stream.complete();
            }
        }), SLOTS::release);
        emitter.onCompletion(() -> stopped.set(true));
        emitter.onError(error -> stopped.set(true));
        emitter.onTimeout(() -> stopped.set(true));
        emitterRef.set(emitter);
        return emitter;
    }

    private static void replay(SseEmitter stream, ScopedMultiAgentService service, ActorRef actor,
                               String id, long cursor, AtomicBoolean stopped) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        stream.send(SseEmitter.event().reconnectTime(1000).comment("connected"));
        while (!stopped.get() && System.nanoTime() < deadline) {
            var page = service.eventsAfter(actor, id, cursor);
            for (var event : page.events()) {
                if (stopped.get()) return;
                service.checkEventPublication(actor, id);
                stream.send(SseEmitter.event().name("run-event").id(Long.toString(event.sequence())).data(event));
                cursor = event.sequence();
            }
            if (cursor < page.lastSequence()) continue;
            if (page.terminal()) {
                service.checkEventPublication(actor, id);
                stream.send(SseEmitter.event().name("caught-up").data(Long.toString(cursor)));
                return;
            }
            if (page.events().isEmpty()) stream.send(SseEmitter.event().comment("heartbeat"));
            Thread.sleep(1000);
        }
    }
}
