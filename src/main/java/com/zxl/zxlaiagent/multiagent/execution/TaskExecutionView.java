package com.zxl.zxlaiagent.multiagent.execution;

import com.zxl.zxlaiagent.multiagent.model.*;
import java.util.*;

public record TaskExecutionView(String id, String description, String capability, TaskStatus status,
                                String assignedAgentId, List<String> dependencies, TaskExecutionContext executionContext,
                                String result, String error, String errorCode, String errorPath, WorkflowError failure,
                                int modelCalls, long startedAt, long completedAt) {
    public static TaskExecutionView from(Task task, String defaultCapability) {
        TaskSnapshot state = task.snapshot();
        Map<String, Object> input = task.getInput() == null ? Map.of() : task.getInput();
        WorkflowError failure = state.failure();
        String capability = !"unknown".equals(defaultCapability) ? defaultCapability
                : input.get("capability") instanceof String value ? value : "unknown";
        return new TaskExecutionView(task.getId(), task.getDescription(), capability,
                state.status(), state.assignedAgentId(), task.getDependencies() == null ? List.of() : List.copyOf(task.getDependencies()),
                input.get("executionContext") instanceof TaskExecutionContext context ? context : null,
                state.result(), failure == null ? null : failure.message(), failure == null ? null : failure.code(),
                failure == null ? null : failure.path(), failure, state.modelCalls(), state.startedAt(), state.completedAt());
    }
}
