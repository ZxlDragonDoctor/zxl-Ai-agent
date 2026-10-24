package com.zxl.zxlaiagent.knowledge.retrieval;

import java.time.Duration;

/** One monotonic deadline shared by HTTP, SQL and optional stages. */
public final class RetrievalDeadline {
    private final long end;
    public RetrievalDeadline(long milliseconds) { end = System.nanoTime() + milliseconds * 1_000_000L; }
    public Duration remaining() {
        if (Thread.currentThread().isInterrupted()) throw new Cancelled();
        long nanos = end - System.nanoTime();
        if (nanos <= 0) throw new Expired();
        return Duration.ofNanos(nanos);
    }
    public static final class Expired extends IllegalStateException { public Expired() { super("RETRIEVAL_DEADLINE_EXCEEDED"); } }
    public static final class Cancelled extends IllegalStateException { public Cancelled() { super("RETRIEVAL_CANCELLED"); } }
}
