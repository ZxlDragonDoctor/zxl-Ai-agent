package com.zxl.zxlaiagent.agent.stream;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/** No worker or frame exists until MVC has attached the emitter's transport handler. */
public final class PreparedAgentStream extends SseEmitter {
    private final AtomicInteger lifecycle = new AtomicInteger(); // 0 prepared, 1 activated, 2 abandoned
    private final Runnable starter;
    private final Runnable abandoned;

    public PreparedAgentStream(long timeout, Runnable starter, Runnable abandoned) {
        super(timeout);
        this.starter = Objects.requireNonNull(starter);
        this.abandoned = Objects.requireNonNull(abandoned);
        onTimeout(this::abandon);
        onError(error -> abandon());
        onCompletion(this::abandon);
    }

    public void activate() {
        if (lifecycle.compareAndSet(0, 1)) {
            try { starter.run(); }
            catch (RuntimeException error) {
                lifecycle.set(2);
                try { abandoned.run(); } finally { complete(); }
                throw error;
            }
        }
    }

    /** Request failures before activation release the run lease without starting its worker. */
    public void abandon() {
        if (lifecycle.compareAndSet(0, 2)) {
            try { abandoned.run(); } finally { complete(); }
        }
    }
}
