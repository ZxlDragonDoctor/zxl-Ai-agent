package com.zxl.zxlaiagent.multiagent.execution;

import com.zxl.zxlaiagent.multiagent.model.*;
import lombok.extern.slf4j.Slf4j;
import java.util.*;
import java.util.function.Consumer;

/** 单次请求的报告器。回调串行发送，不把可变 Task 暴露给订阅者。 */
@Slf4j
public final class WorkflowRun {
    public final String id = UUID.randomUUID().toString();
    public final Task planningTask;
    public volatile Plan plan;
    public volatile Task synthesisTask;
    private final Consumer<Report> consumer;
    private final long startedAt = System.currentTimeMillis();
    private long sequence;
    public WorkflowRun(String goal, Consumer<Report> consumer) {
        planningTask = Task.create(goal);
        this.consumer = consumer;
    }
    public synchronized void emit(String status, String result, WorkflowError error) {
        List<TaskView> tasks = plan == null ? List.of() : plan.getTasks().stream().map(TaskView::from).toList();
        boolean terminal = status.equals("completed") || status.equals("failed");
        Report report = new Report(id, plan == null ? null : plan.getId(), ++sequence, planningTask.getDescription(),
                status, startedAt, terminal ? System.currentTimeMillis() : 0, tasks,
                planningTask.getModelCalls(), tasks.stream().mapToInt(TaskView::modelCalls).sum(),
                synthesisTask == null ? 0 : synthesisTask.getModelCalls(), result, error);
        try { consumer.accept(report); }
        catch (Exception e) { log.debug("Report subscriber disconnected: {}", id); }
    }
    public record Report(String executionId, String planId, long sequence, String goal, String status,
                         long startedAt, long finishedAt, List<TaskView> tasks, int planningModelCalls,
                         int workerModelCalls, int synthesisModelCalls, String result, WorkflowError error) {}
    public record TaskView(String id, String description, String capability, TaskStatus status,
                           String assignedAgentId, List<String> dependencies, TaskExecutionContext executionContext,
                           String result, String error, String errorCode, String errorPath, int modelCalls,
                           long startedAt, long completedAt) {
        static TaskView from(Task task) {
            Map<String, Object> input = task.getInput();
            return new TaskView(task.getId(), task.getDescription(), (String) input.get("capability"),
                    task.getStatus(), task.getAssignedAgentId(), List.copyOf(task.getDependencies()),
                    input.get("executionContext") instanceof TaskExecutionContext context ? context : null,
                    task.getResult(), task.getError(), task.getErrorCode(), task.getErrorPath(),
                    task.getModelCalls(), task.getStartedAt(), task.getCompletedAt());
        }
    }
}
