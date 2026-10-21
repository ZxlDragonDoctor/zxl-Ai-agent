package com.zxl.zxlaiagent.multiagent.runtime.retry;

/** 由适配器明确分类，未知异常默认不重试；message 必须适合返回调用方。 */
public final class RetryFailure extends RuntimeException {
    private final String code;
    private final boolean retryable;
    private RetryFailure(String code, String message, boolean retryable, Throwable cause) {
        super(message, cause); this.code = code; this.retryable = retryable;
    }
    public String code() { return code; }
    public boolean retryable() { return retryable; }
    public static RetryFailure transientFailure(String code, String message) { return new RetryFailure(code, message, true, null); }
    public static RetryFailure permanentFailure(String code, String message) { return new RetryFailure(code, message, false, null); }
    static RetryFailure exhausted(String code, String message, Throwable cause) { return new RetryFailure(code, message, false, cause); }
}
