package com.zxl.zxlaiagent.multiagent.runtime.retry;

import com.zxl.zxlaiagent.multiagent.runtime.ExecutionControl;
import com.zxl.zxlaiagent.multiagent.model.WorkflowException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/** 重试只包裹已声明安全的业务边界，不包裹整条 Agent 轨迹。 */
public final class RetryExecutor {
    @FunctionalInterface public interface Sleeper { void sleep(long millis) throws InterruptedException; }
    public record Result<T>(T value, int attempts) {}
    private final RetryPolicy policy;
    private final Sleeper sleeper;
    private final DoubleSupplier random;
    public RetryExecutor(RetryPolicy policy) { this(policy, Thread::sleep, () -> ThreadLocalRandom.current().nextDouble()); }
    public RetryExecutor(RetryPolicy policy, Sleeper sleeper, DoubleSupplier random) {
        this.policy = Objects.requireNonNull(policy); this.sleeper = Objects.requireNonNull(sleeper); this.random = Objects.requireNonNull(random);
    }
    public <T> Result<T> execute(Supplier<T> operation, ExecutionControl control) {
        for (int attempt = 1; ; attempt++) {
            control.check();
            try {
                T value = operation.get();
                control.check();
                return new Result<>(value, attempt);
            } catch (RetryFailure failure) {
                control.check();
                if (!failure.retryable()) throw failure;
                if (attempt >= policy.maxAttempts()) throw RetryFailure.exhausted("RETRY_EXHAUSTED", "已达到本次操作最大尝试次数", failure);
                long delay = policy.delayAfter(attempt, random.getAsDouble());
                if (delay >= control.waitMillis(Duration.ofMillis(policy.maxDelayMillis()))) {
                    throw RetryFailure.exhausted("RETRY_BUDGET_EXHAUSTED", "剩余运行预算不足以完成退避，不再发起新尝试", failure);
                }
                waitForRetry(delay, control);
            }
        }
    }
    private void waitForRetry(long delay, ExecutionControl control) {
        try {
            for (long left = delay; left > 0;) {
                control.check();
                long slice = Math.min(left, 25);
                sleeper.sleep(slice);
                left -= slice;
            }
            control.check();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new WorkflowException("EXECUTION_INTERRUPTED", "retry", "退避等待已被中断");
        }
    }
}
