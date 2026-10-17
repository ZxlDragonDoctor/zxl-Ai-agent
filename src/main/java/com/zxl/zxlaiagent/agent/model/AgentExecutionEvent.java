package com.zxl.zxlaiagent.agent.model;
import com.zxl.zxlaiagent.tools.support.ToolResults;

/** A public execution event; thinking text is supplied only by the model provider. */
public record AgentExecutionEvent(
        String type,
        int step,
        String callId,
        String toolName,
        String arguments,
        String content,
        String status,
        Long durationMs,
        long timestamp,
        AgentEndReason endReason) {

    public static AgentExecutionEvent of(String type, int step, String content, String status) {
        return new AgentExecutionEvent(type, step, null, null, null, content, status, null,
                System.currentTimeMillis(), null);
    }

    public static AgentExecutionEvent tool(String type, int step, String callId, String toolName,
                                           String arguments, String content, String status, Long durationMs) {
        return new AgentExecutionEvent(type, step, callId, toolName, preview(arguments), ToolResults.preview(content),
                status, durationMs, System.currentTimeMillis(), null);
    }

    public static AgentExecutionEvent terminal(int step, String content, AgentEndReason reason) {
        String status = switch (reason) {
            case STEP_LIMIT -> "limit";
            case TERMINATED_BY_TOOL -> "terminated";
            case OUTPUT_INTERRUPTED -> "interrupted";
            default -> reason.isFailure() ? "error" : "completed";
        };
        return new AgentExecutionEvent(reason.isFailure() ? "RUN_FAILED" : "RUN_COMPLETED", step,
                null, null, null, content, status, null, System.currentTimeMillis(), reason);
    }

    private static String preview(String value) {
        if (value == null || value.length() <= 16000) return value;
        return value.substring(0, 16000) + "\n…（展示内容已截断）";
    }
}
