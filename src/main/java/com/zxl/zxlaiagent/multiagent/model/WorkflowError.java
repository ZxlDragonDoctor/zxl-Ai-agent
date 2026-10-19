package com.zxl.zxlaiagent.multiagent.model;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

public record WorkflowError(String code, String path, String message) {
    public static WorkflowError from(Throwable error) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = error; current != null && seen.add(current); current = current.getCause()) {
            if (current instanceof WorkflowException known) {
                return new WorkflowError(known.getCode(), known.getPath(), known.getMessage());
            }
        }
        return new WorkflowError("EXECUTION_FAILED", "workflow", "执行失败，请查看服务端日志");
    }
}
