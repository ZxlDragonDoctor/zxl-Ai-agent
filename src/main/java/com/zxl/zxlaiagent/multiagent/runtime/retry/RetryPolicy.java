package com.zxl.zxlaiagent.multiagent.runtime.retry;

/** maxAttempts 包含第一次调用。Full jitter: [0, min(cap, base * 2^(attempt-1)))。 */
public record RetryPolicy(int maxAttempts, long baseDelayMillis, long maxDelayMillis) {
    public RetryPolicy {
        if (maxAttempts < 1 || maxAttempts > 10 || baseDelayMillis < 1
                || maxDelayMillis < baseDelayMillis || maxDelayMillis > 60_000) {
            throw new IllegalArgumentException("retry policy exceeds bounded demonstration limits");
        }
    }
    public long delayAfter(int failedAttempt, double random) {
        if (failedAttempt < 1 || failedAttempt >= maxAttempts || random < 0 || random >= 1 || !Double.isFinite(random)) {
            throw new IllegalArgumentException("invalid retry attempt or jitter");
        }
        return (long) (Math.min(maxDelayMillis, baseDelayMillis * (1L << (failedAttempt - 1))) * random);
    }
}
