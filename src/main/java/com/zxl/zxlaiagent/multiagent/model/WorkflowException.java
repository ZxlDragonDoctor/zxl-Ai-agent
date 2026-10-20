package com.zxl.zxlaiagent.multiagent.model;

/** 跨 Future 边界仍可保留的安全业务错误。 */
public class WorkflowException extends RuntimeException {
    private final String code;
    private final String path;
    private final WorkflowError error;
    public WorkflowException(String code, String path, String message) { this(code, path, message, null); }
    public WorkflowException(String code, String path, String message, Throwable cause) {
        this(new WorkflowError(code, path, message), cause);
    }
    public WorkflowException(WorkflowError error) { this(error, null); }
    public WorkflowException(WorkflowError error, Throwable cause) {
        super(error.message(), cause);
        this.code = error.code();
        this.path = error.path();
        this.error = error;
    }
    public String getCode() { return code; }
    public String getPath() { return path; }
    public WorkflowError getError() { return error; }
}
