package com.zxl.zxlaiagent.multiagent.execution;

import com.zxl.zxlaiagent.multiagent.model.*;
import java.util.*;

public final class FailurePropagation {
    private FailurePropagation() {}

    public static WorkflowError firstFailure(Plan plan, WorkflowError fallback) {
        if (plan == null) return fallback;
        return plan.getTasks().stream().map(Task::snapshot)
                .filter(state -> state.failure() != null && state.status() == TaskStatus.FAILED)
                .min(Comparator.comparingLong(TaskSnapshot::completedAt)).map(TaskSnapshot::failure).orElse(fallback);
    }

    public static List<Task> blockPending(Plan plan, WorkflowError root) {
        if (plan == null) return List.of();
        Map<String, Task> byId = new HashMap<>();
        plan.getTasks().forEach(task -> byId.put(task.getId(), task));
        List<Task> changed = new ArrayList<>();
        boolean progress;
        do {
            progress = false;
            for (Task task : plan.getTasks()) {
                if (task.getStatus() != TaskStatus.PENDING) continue;
                List<Task> failedDependencies = task.getDependencies().stream().map(byId::get).filter(Objects::nonNull)
                        .filter(dependency -> TaskLifecycle.terminal(dependency.getStatus()) && dependency.getStatus() != TaskStatus.COMPLETED).toList();
                if (failedDependencies.isEmpty()) continue;
                List<String> roots = failedDependencies.stream().map(dependency -> {
                    WorkflowError cause = dependency.snapshot().failure();
                    return cause == null || cause.sourceTaskId() == null ? dependency.getId() : cause.sourceTaskId();
                }).distinct().toList();
                // 先保留本次已选择、且确实位于此依赖链中的根因；其他分支按根任务时间选择。
                String source = roots.contains(root.sourceTaskId()) ? root.sourceTaskId() : plan.getTasks().stream()
                        .filter(candidate -> roots.contains(candidate.getId()))
                        .min(Comparator.comparingLong(candidate -> candidate.snapshot().completedAt()))
                        .map(Task::getId).orElse(roots.get(0));
                WorkflowError error = new WorkflowError("DEPENDENCY_FAILED", "tasks." + task.getId() + ".dependencies",
                        "必需依赖未成功完成", source, failedDependencies.stream().map(Task::getId).toList(), "DEPENDENCY_FAILED");
                if (TaskLifecycle.block(task, error).applied()) { changed.add(task); progress = true; }
            }
        } while (progress);
        for (Task task : plan.getTasks()) {
            var error = new WorkflowError("PLAN_ABORTED", "tasks." + task.getId(), "计划遇错停止后续调度",
                    root.sourceTaskId(), List.of(), "PLAN_ABORTED");
            if (TaskLifecycle.block(task, error).applied()) changed.add(task);
        }
        return List.copyOf(changed);
    }
}
