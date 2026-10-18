package com.zxl.zxlaiagent.conversation;

import org.springframework.http.HttpStatus;

/** HTTP 层只暴露稳定错误码与安全说明，文件路径和原始异常留给服务端日志。 */
public class ConversationException extends RuntimeException {
    private final String code;
    private final HttpStatus status;

    public ConversationException(String code, String message, HttpStatus status) {
        this(code, message, status, null);
    }

    public ConversationException(String code, String message, HttpStatus status, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.status = status;
    }

    public String getCode() { return code; }
    public HttpStatus getStatus() { return status; }
}
