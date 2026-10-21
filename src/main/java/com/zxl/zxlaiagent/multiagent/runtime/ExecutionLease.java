package com.zxl.zxlaiagent.multiagent.runtime;

import java.util.concurrent.atomic.AtomicBoolean;

/** 归还动作只能由实际工作的完成路径调用；重复 close 无效。 */
public final class ExecutionLease implements AutoCloseable {
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Runnable release;
    public ExecutionLease(Runnable release) { this.release = release; }
    @Override public void close() { if (closed.compareAndSet(false, true)) release.run(); }
}
