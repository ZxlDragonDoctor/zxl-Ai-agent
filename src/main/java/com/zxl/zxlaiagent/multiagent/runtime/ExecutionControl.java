package com.zxl.zxlaiagent.multiagent.runtime;

import com.zxl.zxlaiagent.multiagent.model.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.*;

/** 仅存在于应用运行时；不进入 Task.input、JSON 或 Prompt。 */
public final class ExecutionControl {
    private final long acceptedAt, budgetNanos, startNanos;
    private final LongSupplier clock;
    private WorkflowError stopped;
    public ExecutionControl(long acceptedAt, Duration budget, LongSupplier clock) {
        this.acceptedAt = acceptedAt;
        this.budgetNanos = budget.toNanos();
        if (budgetNanos <= 0) throw new IllegalArgumentException("budget must be positive");
        this.clock = clock;
        this.startNanos = clock.getAsLong();
    }
    public long acceptedAt() { return acceptedAt; }
    public long deadlineAt() { return acceptedAt + TimeUnit.NANOSECONDS.toMillis(budgetNanos); }
    public synchronized WorkflowError stopReason() {
        if (stopped == null && remainingNanos() <= 0) stopped = error("RUN_DEADLINE_EXCEEDED", "运行总预算已耗尽", "TIMEOUT");
        return stopped;
    }
    public synchronized boolean cancel() {
        if (stopReason() != null) return false;
        stopped = error("RUN_CANCELLED", "用户已取消本次运行", "CANCELLED");
        return true;
    }
    public synchronized void stop(WorkflowError reason) { if (stopReason() == null) stopped = reason; }
    public synchronized void check() {
        WorkflowError reason = stopReason();
        if (reason != null) throw new WorkflowException(reason);
        if (Thread.currentThread().isInterrupted()) throw new WorkflowException("EXECUTION_INTERRUPTED", "runtime", "工作线程已被中断");
    }
    /** 只包含领取/提交等短操作；禁止在此锁内执行阻塞 I/O。 */
    public synchronized <T> T admit(Supplier<T> boundary) { check(); return boundary.get(); }
    public synchronized long waitMillis(Duration phaseLimit) {
        check();
        return Math.max(1, Math.min(phaseLimit.toMillis(), TimeUnit.NANOSECONDS.toMillis(remainingNanos())));
    }
    private long remainingNanos() { return budgetNanos - (clock.getAsLong() - startNanos); }
    private static WorkflowError error(String code, String message, String reason) {
        return new WorkflowError(code, "runtime", message, null, List.of(), reason);
    }
}
