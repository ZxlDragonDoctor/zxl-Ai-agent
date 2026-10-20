package com.zxl.zxlaiagent.multiagent.model;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.List;

public record WorkflowError(String code, String path, String message, String sourceTaskId,
                            List<String> blockedByTaskIds, String reason) {
    public WorkflowError { blockedByTaskIds = blockedByTaskIds == null ? List.of() : List.copyOf(blockedByTaskIds); }
    public WorkflowError(String code, String path, String message) {
        this(code, path, message, null, List.of(), code.contains("TIMEOUT") ? "TIMEOUT" : "EXECUTION_ERROR");
    }
    public WorkflowError forTask(String taskId) {
        return new WorkflowError(code, "workflow".equals(path) ? "tasks." + taskId : path, message,
                sourceTaskId == null ? taskId : sourceTaskId, blockedByTaskIds, reason);
    }
    public static WorkflowError from(Throwable error) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = error; current != null && seen.add(current); current = current.getCause()) {
            if (current instanceof WorkflowException known) {
                return known.getError();
            }
        }
        return new WorkflowError("EXECUTION_FAILED", "workflow", "执行失败，请查看服务端日志");
    }
}
