package com.zxl.zxlaiagent.multiagent.execution;

import java.util.*;

/** 每个接收者独享快照；不持有运行中 Plan 或 Task 的引用。 */
public record TaskExecutionContext(String originalGoal, Map<String, Object> userInput,
                                   List<DependencyResult> dependencyResults) {
    public TaskExecutionContext {
        userInput = TaskInputBuilder.freezeMap(userInput);
        dependencyResults = List.copyOf(dependencyResults);
    }
    public record DependencyResult(String taskId, String description, String agentId, String output) {}
}
