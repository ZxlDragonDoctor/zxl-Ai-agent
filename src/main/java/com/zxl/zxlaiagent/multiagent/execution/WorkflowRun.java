package com.zxl.zxlaiagent.multiagent.execution;

import com.zxl.zxlaiagent.multiagent.model.*;
import lombok.extern.slf4j.Slf4j;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import com.zxl.zxlaiagent.multiagent.runtime.RuntimeView;

@Slf4j
public final class WorkflowRun {
    public final String id = UUID.randomUUID().toString();
    public final Task planningTask;
    public volatile Plan plan;
    public volatile Task synthesisTask;
    private final Consumer<Report> consumer;
    private final long startedAt = System.currentTimeMillis();
    private long sequence;
    private volatile WorkflowOutcome outcome;
    private volatile Report latest;
    private Supplier<RuntimeView> runtimeView = () -> null;
    public synchronized void setRuntimeView(Supplier<RuntimeView> view) { runtimeView = view; }
    public WorkflowOutcome outcome() { return outcome; }
    public Report latestReport() { return latest; }
    public WorkflowRun(String goal, Consumer<Report> consumer) {
        planningTask = Task.create(goal);
        this.consumer = Objects.requireNonNull(consumer);
    }
    public synchronized void emit(String stage, String unusedResult, WorkflowError unusedError) {
        if (outcome != null) return;
        if (!Set.of("queued", "planning", "running", "synthesizing").contains(stage))
            throw new IllegalArgumentException("Use finishSuccess/finishFailure for terminal outcomes");
        send(report(stage, null));
    }
    public synchronized WorkflowOutcome finishSuccess(String result) {
        if (outcome != null) return outcome;
        List<TaskExecutionView> tasks = taskViews();
        TaskExecutionView synthesis = synthesisTask == null ? null : TaskExecutionView.from(synthesisTask, "supervisor");
        if (plan == null || planningTask.getStatus() != TaskStatus.COMPLETED || tasks.isEmpty() || tasks.stream().anyMatch(task -> task.status() != TaskStatus.COMPLETED
                || task.result() == null || task.result().isBlank()) || result == null || result.isBlank()
                || (synthesis != null && synthesis.status() != TaskStatus.COMPLETED))
            return finishFailure(new WorkflowError("PLAN_INCOMPLETE", "workflow", "必需任务或汇总未成功，不能判定计划完成"));
        return finish("completed", result, null, tasks, synthesis);
    }
    public synchronized WorkflowOutcome finishFailure(WorkflowError error) {
        if (outcome != null) return outcome;
        return finish("failed", null, Objects.requireNonNull(error), taskViews(),
                synthesisTask == null ? null : TaskExecutionView.from(synthesisTask, "supervisor"));
    }
    public synchronized WorkflowOutcome finishCancelled(WorkflowError error) {
        if (outcome != null) return outcome;
        return finish("cancelled", null, Objects.requireNonNull(error), taskViews(),
                synthesisTask == null ? null : TaskExecutionView.from(synthesisTask, "supervisor"));
    }
    private WorkflowOutcome finish(String status, String result, WorkflowError error, List<TaskExecutionView> tasks, TaskExecutionView synthesis) {
        TaskExecutionView planning = TaskExecutionView.from(planningTask, "planner");
        outcome = new WorkflowOutcome(id, plan == null ? null : plan.getId(), planningTask.getDescription(), status,
                startedAt, System.currentTimeMillis(), tasks, planning, synthesis, planning.modelCalls(),
                tasks.stream().mapToInt(TaskExecutionView::modelCalls).sum(), synthesis == null ? 0 : synthesis.modelCalls(), result, error);
        if (plan != null) plan.setStatus(outcome.successful() ? PlanStatus.COMPLETED : status.equals("cancelled") ? PlanStatus.CANCELLED : PlanStatus.FAILED);
        send(report(status, outcome));
        return outcome;
    }
    private List<TaskExecutionView> taskViews() {
        return plan == null ? List.of() : plan.getTasks().stream().map(task -> TaskExecutionView.from(task, "unknown")).toList();
    }
    private Report report(String status, WorkflowOutcome terminal) {
        List<TaskExecutionView> tasks = terminal == null ? taskViews() : terminal.tasks();
        TaskExecutionView planning = terminal == null ? TaskExecutionView.from(planningTask, "planner") : terminal.planningTask();
        TaskExecutionView synthesis = terminal == null ? (synthesisTask == null ? null : TaskExecutionView.from(synthesisTask, "supervisor")) : terminal.synthesisTask();
        return new Report(id, plan == null ? null : plan.getId(), ++sequence, planningTask.getDescription(), status, startedAt,
                terminal == null ? 0 : terminal.finishedAt(), tasks, planning.modelCalls(), tasks.stream().mapToInt(TaskExecutionView::modelCalls).sum(),
                synthesis == null ? 0 : synthesis.modelCalls(), terminal == null ? null : terminal.result(),
                terminal == null ? null : terminal.error(), planning, synthesis, terminal, runtimeView.get());
    }
    private void send(Report report) {
        latest = report;
        try { consumer.accept(report); }
        catch (Exception e) { log.debug("Report subscriber disconnected: {}", id); }
    }
    public record Report(String executionId, String planId, long sequence, String goal, String status,
                         long startedAt, long finishedAt, List<TaskExecutionView> tasks, int planningModelCalls,
                         int workerModelCalls, int synthesisModelCalls, String result, WorkflowError error,
                         TaskExecutionView planningTask, TaskExecutionView synthesisTask, WorkflowOutcome outcome, RuntimeView runtime) {
        public Report withRuntime(RuntimeView view) { return new Report(executionId, planId, sequence, goal, status, startedAt, finishedAt,
                tasks, planningModelCalls, workerModelCalls, synthesisModelCalls, result, error, planningTask, synthesisTask, outcome, view); }
    }
}
