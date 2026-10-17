package com.zxl.zxlaiagent.tools.model;

/** Contract shared by model context and execution UI. */
public record ToolResult<T>(boolean success, String code, String message, T data) {
    public ToolResult {
        if (code == null || code.isBlank() || message == null || message.isBlank())
            throw new IllegalArgumentException("工具结果必须包含 code 和 message");
        if (!success && "OK".equals(code)) throw new IllegalArgumentException("失败结果不能使用 OK");
    }
    public static <T> ToolResult<T> ok(String message, T data) {
        return new ToolResult<>(true, "OK", message, data);
    }
    public static <T> ToolResult<T> fail(String code, String message) {
        return new ToolResult<>(false, code, message, null);
    }
    public static <T> ToolResult<T> fail(String code, String message, T data) {
        return new ToolResult<>(false, code, message, data);
    }
}
