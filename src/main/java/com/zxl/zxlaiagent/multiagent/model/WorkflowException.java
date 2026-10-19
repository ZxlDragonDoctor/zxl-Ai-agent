package com.zxl.zxlaiagent.multiagent.model;

/** 跨 Future 边界仍可保留的安全业务错误。 */
public class WorkflowException extends RuntimeException {
    private final String code;
    private final String path;
    public WorkflowException(String code, String path, String message) { this(code, path, message, null); }
    public WorkflowException(String code, String path, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.path = path;
    }
    public String getCode() { return code; }
    public String getPath() { return path; }
}
