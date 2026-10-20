package com.zxl.zxlaiagent.multiagent.model;

import java.util.Objects;
import java.util.function.UnaryOperator;

/** Task 执行快照的唯一写入者。无效迁移返回 applied=false，保留原快照。 */
public final class TaskLifecycle {
    private TaskLifecycle() {}
    public record Transition(boolean applied, TaskSnapshot snapshot) {}

    private static Transition update(Task task, UnaryOperator<TaskSnapshot> operation) {
        while (true) {
            TaskSnapshot before = task.snapshot();
            TaskSnapshot after = operation.apply(before);
            if (after == before) return new Transition(false, before);
            if (task.compareExecution(before, after)) return new Transition(true, after);
        }
    }
    public static Transition start(Task task, String agentId) {
        Objects.requireNonNull(agentId);
        return update(task, s -> s.status() != TaskStatus.PENDING ? s :
                new TaskSnapshot(TaskStatus.IN_PROGRESS, null, null, agentId, s.modelCalls(), System.currentTimeMillis(), 0));
    }
    public static Transition complete(Task task, String output) {
        if (output == null || output.isBlank()) return fail(task, new WorkflowError("TASK_OUTPUT_MISSING", "tasks." + task.getId(), "Agent 未返回有效内容"));
        return update(task, s -> s.status() != TaskStatus.IN_PROGRESS ? s :
                new TaskSnapshot(TaskStatus.COMPLETED, output, null, s.assignedAgentId(), s.modelCalls(), s.startedAt(), finishTime(s)));
    }
    public static Transition fail(Task task, WorkflowError error) {
        Objects.requireNonNull(error);
        return update(task, s -> s.status() != TaskStatus.IN_PROGRESS ? s :
                new TaskSnapshot(TaskStatus.FAILED, null, error.forTask(task.getId()), s.assignedAgentId(), s.modelCalls(), s.startedAt(), finishTime(s)));
    }
    public static Transition block(Task task, WorkflowError error) {
        Objects.requireNonNull(error);
        return update(task, s -> s.status() != TaskStatus.PENDING ? s :
                new TaskSnapshot(TaskStatus.BLOCKED, null, error, s.assignedAgentId(), s.modelCalls(), s.startedAt(), finishTime(s)));
    }
    public static Transition cancel(Task task, WorkflowError error) {
        Objects.requireNonNull(error);
        return update(task, s -> terminal(s.status()) ? s :
                new TaskSnapshot(TaskStatus.CANCELLED, null, error, s.assignedAgentId(), s.modelCalls(), s.startedAt(), finishTime(s)));
    }
    public static boolean recordModelCall(Task task) {
        return update(task, s -> s.status() != TaskStatus.IN_PROGRESS ? s :
                new TaskSnapshot(s.status(), s.result(), s.failure(), s.assignedAgentId(), s.modelCalls() + 1, s.startedAt(), 0)).applied();
    }
    public static boolean terminal(TaskStatus status) {
        return status == TaskStatus.COMPLETED || status == TaskStatus.FAILED || status == TaskStatus.BLOCKED || status == TaskStatus.CANCELLED;
    }
    private static long finishTime(TaskSnapshot s) { return Math.max(System.currentTimeMillis(), s.startedAt()); }
}
