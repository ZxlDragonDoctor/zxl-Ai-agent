package com.zxl.zxlaiagent.multiagent.orchestration;

import com.fasterxml.jackson.databind.*;
import com.zxl.zxlaiagent.multiagent.agents.AbstractBaseAgent;
import com.zxl.zxlaiagent.multiagent.bus.AgentMessageBus;
import com.zxl.zxlaiagent.multiagent.execution.*;
import com.zxl.zxlaiagent.multiagent.model.*;
import com.zxl.zxlaiagent.multiagent.planning.*;
import com.zxl.zxlaiagent.multiagent.runtime.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.Collectors;

@Slf4j
public class OrchestratorAgent implements AgentInterface {
    private final String agentId = "orchestrator-1";
    private final AgentCapability capability = AgentCapability.builder().agentType("orchestrator")
            .description("Validated, bounded workflow execution").tools(Set.of("plan_execution", "agent_discovery"))
            .domains(Set.of("orchestration")).maxConcurrency(1).canDelegate(true).build();
    private final ObjectMapper mapper;
    private final PlanValidator validator;
    private final TaskInputBuilder inputs;
    private final RuntimeSettings settings;
    private final LongSupplier clock;
    private final ExecutionRuntime runtime;
    private final RunRegistry registry;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "agent-deadline"); t.setDaemon(true); return t;
    });
    private AgentMessageBus messageBus;
    private final Map<String, Plan> activePlans = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<String>> pendingTasks = new ConcurrentHashMap<>();
    private final Map<String, AgentInterface> agentDirectory = new ConcurrentHashMap<>();
    public OrchestratorAgent(ChatModel model) { this(model, new RuntimeSettings(), System::nanoTime); }
    public OrchestratorAgent(ChatModel model, RuntimeSettings settings, LongSupplier clock) {
        this(model, new ObjectMapper(), new PlanValidator(), new TaskInputBuilder(new ObjectMapper()), settings, clock);
    }
    public OrchestratorAgent(ChatModel model, ObjectMapper mapper, PlanValidator validator, TaskInputBuilder inputs) {
        this(model, mapper, validator, inputs, new RuntimeSettings(), System::nanoTime);
    }
    public OrchestratorAgent(ChatModel model, ObjectMapper mapper, PlanValidator validator, TaskInputBuilder inputs, RuntimeSettings settings, LongSupplier clock) {
        this.mapper = mapper; this.validator = validator; this.inputs = inputs; this.settings = settings; this.clock = clock;
        runtime = new ExecutionRuntime(settings); registry = new RunRegistry(settings);
        timer.scheduleWithFixedDelay(() -> { try { sweepRuns(); } catch (Exception e) { log.warn("Runtime sweep failed", e); } }, 25, 25, TimeUnit.MILLISECONDS);
    }
    public void init(AgentMessageBus bus) {
        messageBus = bus; bus.registerAgent(this);
        bus.addEventListener(EventType.AGENT_REGISTERED, this::onEvent); bus.addEventListener(EventType.AGENT_UNREGISTERED, this::onEvent);
    }
    @Override public String getAgentId() { return agentId; }
    @Override public String getAgentType() { return "orchestrator"; }
    @Override public AgentState getState() { return AgentState.IDLE; }
    @Override public AgentCapability getCapability() { return capability; }
    @Override public CompletableFuture<String> executeTask(Task task) {
        if (!TaskLifecycle.start(task, agentId).applied()) return CompletableFuture.failedFuture(new WorkflowException("TASK_ALREADY_STARTED", "tasks." + task.getId(), "任务已开始或结束"));
        try {
            return submit(task.getDescription(), task.getInput(), null, report -> {}).result().thenApply(outcome -> {
                if (!outcome.successful()) { TaskLifecycle.fail(task, outcome.error()); throw new WorkflowException(outcome.error()); }
                TaskLifecycle.complete(task, outcome.result()); return outcome.result();
            });
        } catch (Exception e) { TaskLifecycle.fail(task, WorkflowError.from(e)); return CompletableFuture.failedFuture(e); }
    }
    public record RunHandle(String executionId, CompletableFuture<WorkflowOutcome> result) {}
    public record CancelReceipt(String status, RunRegistry.View run) {}
    public RunHandle submit(String goal, Map<String, Object> context, Long timeoutMs, Consumer<WorkflowRun.Report> reports) {
        ExecutionControl control = new ExecutionControl(System.currentTimeMillis(), Duration.ofMillis(settings.budget(timeoutMs)), clock);
        WorkflowRun run = new WorkflowRun(goal, reports);
        RunRegistry.Entry entry = registry.register(run, control);
        entry.queueStartedNanos = clock.getAsLong(); entry.waitingFor = "run";
        run.emit("queued", null, null);
        try {
            if (goal == null || goal.isBlank() || goal.length() > 12000) throw new WorkflowException("INVALID_GOAL", "goal", "目标不能为空且不能超过 12000 字符");
            Map<String, Object> userInput = inputs.snapshotUserInput(context);
            Runnable work = () -> {
                synchronized (run) {
                    if (run.outcome() != null) return;
                    entry.coordinating = true; entry.waitingFor = null;
                }
                try { executeRun(entry, userInput); }
                finally { entry.coordinating = false; if (run.plan != null) activePlans.remove(run.plan.getId()); }
            };
            entry.queuedWork = work;
            synchronized (run) { control.admit(() -> { runtime.dispatch(work); return null; }); }
        } catch (Exception e) { finishError(entry, WorkflowError.from(e)); }
        return new RunHandle(run.id, entry.result);
    }
    public String orchestrate(String goal, Map<String, Object> context) { return orchestrate(goal, context, r -> {}); }
    public String orchestrate(String goal, Map<String, Object> context, Consumer<WorkflowRun.Report> reports) {
        WorkflowOutcome outcome = run(goal, context, reports);
        if (!outcome.successful()) throw new WorkflowException(outcome.error()); return outcome.result();
    }
    public WorkflowOutcome run(String goal, Map<String, Object> context, Consumer<WorkflowRun.Report> reports) { return run(goal, context, null, reports); }
    public WorkflowOutcome run(String goal, Map<String, Object> context, Long timeoutMs, Consumer<WorkflowRun.Report> reports) {
        try { return submit(goal, context, timeoutMs, reports).result().get(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new WorkflowException("REQUEST_INTERRUPTED", "request", "请求等待被中断，业务状态可通过 executionId 查询", e); }
        catch (ExecutionException e) { throw new WorkflowException(WorkflowError.from(e), e); }
    }
    private void executeRun(RunRegistry.Entry entry, Map<String, Object> userInput) {
        WorkflowRun run = entry.run;
        try {
            entry.control.check(); run.emit("planning", null, null);
            publish(EventType.PLAN_STARTED, Map.of("executionId", run.id, "goal", run.planningTask.getDescription()));
            run.planningTask.setInput(userInput);
            String json = executePhase(run.planningTask, "planner", settings.getPlanningTimeoutMs(), "planning", entry);
            Plan candidate;
            try { candidate = mapper.readerFor(Plan.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_IGNORED_PROPERTIES).readValue(json); }
            catch (Exception e) { throw new WorkflowException("INVALID_PLAN_JSON", "planning", "规划角色返回的运行时计划无法读取", e); }
            validator.validate(candidate, agentDirectory.values().stream().map(AgentInterface::getAgentType).collect(Collectors.toSet()));
            if (!run.planningTask.getDescription().equals(candidate.getGoal())) throw new WorkflowException("INVALID_PLAN_FIELD", "goal", "规划目标与原始目标不一致");
            synchronized (run) {
                entry.control.check(); run.plan = candidate; activePlans.put(candidate.getId(), candidate); candidate.setStatus(PlanStatus.IN_PROGRESS);
            }
            run.emit("running", null, null);
            executePlan(entry, userInput);
            String result = synthesize(entry, userInput);
            synchronized (run) {
                entry.control.check(); WorkflowOutcome outcome = run.finishSuccess(result); entry.result.complete(outcome);
                publish(outcome.successful() ? EventType.PLAN_COMPLETED : EventType.PLAN_FAILED, Map.of("executionId", run.id, "outcome", outcome));
            }
        } catch (Exception e) { finishError(entry, WorkflowError.from(e)); }
    }
    private void executePlan(RunRegistry.Entry entry, Map<String, Object> userInput) {
        WorkflowRun run = entry.run; Plan plan = run.plan;
        while (!plan.isComplete()) {
            entry.control.check(); List<Task> ready = plan.getReadyTasks();
            if (ready.isEmpty()) throw new WorkflowException("PLAN_STALLED", "tasks", "计划未完成且没有可执行任务");
            Map<Task, ExecutionRuntime.AgentLease> wave = new LinkedHashMap<>();
            for (Task task : ready) {
                String role = role(task); requireType(role);
                var lease = entry.control.admit(() -> runtime.tryAgent(role, agentDirectory.values()));
                if (lease != null) wave.put(task, lease);
            }
            if (wave.isEmpty()) wave.put(ready.get(0), acquire(role(ready.get(0)), entry));
            Set<Task> launched = new HashSet<>(); List<Work> work = new ArrayList<>();
            try {
                for (Task task : wave.keySet()) {
                    TaskExecutionContext context = inputs.build(plan, task, userInput);
                    task.setInput(Map.of("capability", role(task), "executionContext", context));
                }
                for (var item : wave.entrySet()) {
                    Work started = startWork(item.getKey(), item.getValue(), entry, "running");
                    launched.add(item.getKey()); work.add(started);
                }
                WorkflowError first = null;
                for (Work started : work) {
                    try { awaitWork(started, settings.getTaskTimeoutMs(), "tasks." + started.task.getId(), entry); }
                    catch (WorkflowException e) { if (first == null) first = e.getError(); }
                }
                if (first != null) throw new WorkflowException(first);
            } finally {
                wave.forEach((task, lease) -> { if (!launched.contains(task)) lease.close(); }); run.emit("running", null, null);
            }
        }
    }
    private String role(Task task) { return PlanCapabilities.normalize((String) task.getInput().get("capability"), "tasks." + task.getId() + ".capability"); }
    private void requireType(String role) {
        if (agentDirectory.values().stream().noneMatch(a -> role.equals(a.getAgentType()))) throw new WorkflowException("AGENT_TYPE_NOT_REGISTERED", "agents." + role, "工作角色未注册：" + role);
    }
    private ExecutionRuntime.AgentLease acquire(String role, RunRegistry.Entry entry) {
        requireType(role);
        try { return runtime.awaitAgent(role, () -> List.copyOf(agentDirectory.values()), entry.control,
                () -> { entry.waitingFor = "agent:" + role; entry.run.emit("queued", null, null); }); }
        finally { entry.waitingFor = null; }
    }
    private String executePhase(Task task, String role, long phaseMs, String stage, RunRegistry.Entry entry) {
        return awaitWork(startWork(task, acquire(role, entry), entry, stage), phaseMs, stage, entry);
    }
    private record Work(Task task, CompletableFuture<String> future, long startedNanos) {}
    private Work startWork(Task task, ExecutionRuntime.AgentLease agent, RunRegistry.Entry entry, String stage) {
        ExecutionLease occupancy = entry.taskLease(); runtime.bind(task, entry);
        try {
            CompletableFuture<String> actual = entry.control.admit(() -> agent.agent().executeTask(task));
            if (actual == null) throw new WorkflowException("NULL_TASK_FUTURE", stage, "Agent 返回了 null Future");
            pendingTasks.put(task.getId(), actual);
            actual.whenComplete((value, error) -> {
                try { pendingTasks.remove(task.getId(), actual); runtime.unbind(task); }
                finally { try { occupancy.close(); } finally { agent.close(); } }
            });
            entry.run.emit(stage, null, null); return new Work(task, actual, clock.getAsLong());
        } catch (Exception e) { runtime.unbind(task); occupancy.close(); agent.close(); throw e; }
    }
    private String awaitWork(Work work, long phaseMs, String path, RunRegistry.Entry entry) {
        try {
            while (true) {
                entry.control.check();
                long remaining = phaseMs - TimeUnit.NANOSECONDS.toMillis(clock.getAsLong() - work.startedNanos);
                if (remaining <= 0) {
                    WorkflowError timeout = new WorkflowError("TASK_TIMEOUT", path, "工作阶段预算已耗尽").forTask(work.task.getId());
                    entry.control.stop(timeout); throw new WorkflowException(timeout);
                }
                try {
                    String output = work.future.get(Math.min(25, entry.control.waitMillis(Duration.ofMillis(remaining))), TimeUnit.MILLISECONDS);
                    TaskSnapshot snapshot = work.task.snapshot();
                    if (snapshot.status() != TaskStatus.COMPLETED || output == null || output.isBlank() || !Objects.equals(snapshot.result(), output))
                        throw new WorkflowException("TASK_STATE_MISMATCH", path, "Future 与任务完成快照不一致");
                    return output;
                } catch (TimeoutException polling) { /* 轮询等待不等于业务超时。 */ }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); entry.control.stop(new WorkflowError("EXECUTION_INTERRUPTED", path, "执行等待被中断"));
            throw new WorkflowException(entry.control.stopReason(), e);
        } catch (Exception e) {
            WorkflowError error = WorkflowError.from(e).forTask(work.task.getId());
            if (entry.control.stopReason() == null) terminateTask(work.task, error, false);
            WorkflowError authoritative = work.task.snapshot().failure() == null ? error : work.task.snapshot().failure();
            if (path.equals("synthesis") || (path.equals("planning") && authoritative.path().equals("tasks." + work.task.getId())))
                authoritative = new WorkflowError(authoritative.code(), path, authoritative.message(), authoritative.sourceTaskId(), authoritative.blockedByTaskIds(), authoritative.reason());
            throw new WorkflowException(authoritative, e);
        }
    }
    private String synthesize(RunRegistry.Entry entry, Map<String, Object> userInput) {
        entry.control.check(); WorkflowRun run = entry.run; Plan plan = run.plan;
        if (!plan.isComplete()) throw new WorkflowException("PLAN_INCOMPLETE", "tasks", "未完成的计划不能汇总为成功");
        if (plan.getTasks().size() == 1) return plan.getTasks().get(0).getResult();
        Set<String> dependencies = plan.getTasks().stream().flatMap(t -> t.getDependencies().stream()).collect(Collectors.toSet());
        List<Task> sinks = plan.getTasks().stream().filter(t -> !dependencies.contains(t.getId())).toList();
        if (sinks.size() == 1 && "supervisor".equals(sinks.get(0).getInput().get("capability"))) return sinks.get(0).getResult();
        Task synthesis = Task.create("根据全部成功结果回答原始目标：" + plan.getGoal(), plan.getTasks().stream().map(Task::getId).toList());
        synchronized (run) { entry.control.check(); run.synthesisTask = synthesis; }
        try {
            String results = plan.getTasks().stream().map(t -> "=== " + t.getDescription() + " ===\n" + t.getResult()).collect(Collectors.joining("\n\n"));
            if (results.length() > 100000) throw new WorkflowException("DEPENDENCY_OUTPUT_TOO_LARGE", "synthesis", "汇总内容超过上下文预算");
            synthesis.setInput(Map.of("plan", plan, "allResults", results, "capability", "supervisor", "executionContext", inputs.build(plan, synthesis, userInput)));
            return executePhase(synthesis, "supervisor", settings.getSynthesisTimeoutMs(), "synthesizing", entry);
        } catch (Exception e) {
            WorkflowError original = WorkflowError.from(e).forTask(synthesis.getId());
            WorkflowError failure = new WorkflowError(original.code(), "synthesis", original.message(), original.sourceTaskId(), original.blockedByTaskIds(), original.reason());
            if (entry.control.stopReason() == null) terminateTask(synthesis, failure, false);
            throw new WorkflowException(failure, e);
        }
    }
    private void finishError(RunRegistry.Entry entry, WorkflowError failure) {
        synchronized (entry.run) {
            if (entry.run.outcome() != null) return;
            WorkflowError stop = entry.control.stopReason(); boolean cancelled = stop != null && stop.code().equals("RUN_CANCELLED");
            if (stop != null) failure = stop; else failure = FailurePropagation.firstFailure(entry.run.plan, failure);
            if (stop != null) {
                terminateTask(entry.run.planningTask, failure, cancelled);
                if (entry.run.plan != null) for (Task task : entry.run.plan.getTasks()) terminateTask(task, failure, cancelled);
            } else {
                terminateTask(entry.run.planningTask, failure, false);
                for (Task task : FailurePropagation.blockPending(entry.run.plan, failure)) publishTask(EventType.TASK_BLOCKED, task);
            }
            if (entry.run.synthesisTask != null) terminateTask(entry.run.synthesisTask, failure, cancelled);
            if (entry.queuedWork != null) runtime.removeQueued(entry.queuedWork);
            entry.waitingFor = null;
            WorkflowOutcome outcome = cancelled ? entry.run.finishCancelled(failure) : entry.run.finishFailure(failure); entry.result.complete(outcome);
            publish(EventType.PLAN_FAILED, Map.of("executionId", entry.run.id, "outcome", outcome));
        }
    }
    private void terminateTask(Task task, WorkflowError error, boolean cancelled) {
        TaskLifecycle.Transition transition = cancelled ? TaskLifecycle.cancel(task, error.forTask(task.getId())) :
                task.getStatus() == TaskStatus.PENDING ? TaskLifecycle.block(task, error.forTask(task.getId())) : TaskLifecycle.fail(task, error);
        if (transition.applied()) publishTask(cancelled ? EventType.TASK_CANCELLED : transition.snapshot().status() == TaskStatus.BLOCKED ? EventType.TASK_BLOCKED : EventType.TASK_FAILED, task);
    }
    public CancelReceipt cancel(String id) {
        RunRegistry.Entry entry = registry.find(id); if (entry == null) return new CancelReceipt("not_found", null);
        synchronized (entry.run) {
            if (entry.run.outcome() != null) return new CancelReceipt("already_finished", registry.view(id));
            boolean accepted = entry.control.cancel(); finishError(entry, entry.control.stopReason());
            return new CancelReceipt(accepted ? "accepted" : "already_finished", registry.view(id));
        }
    }
    public RunRegistry.View runStatus(String id) { return registry.view(id); }
    public Map<String, Object> runtimeMetrics() { return runtime.metrics(); }
    public void sweepRuns() {
        for (RunRegistry.Entry entry : registry.entries()) {
            if (entry.run.outcome() != null) continue;
            if (!entry.coordinating && clock.getAsLong() - entry.queueStartedNanos >= TimeUnit.MILLISECONDS.toNanos(settings.getQueueTimeoutMs()))
                entry.control.stop(new WorkflowError("CAPACITY_QUEUE_TIMEOUT", "runs.queue", "运行排队时间已达上限"));
            WorkflowError stop = entry.control.stopReason(); if (stop != null) finishError(entry, stop);
        }
    }
    private void publish(EventType type, Map<String, Object> data) {
        try { if (messageBus != null) messageBus.publishEvent(AgentEvent.of(agentId, type, data)); }
        catch (Exception e) { log.warn("Workflow observation failed: {}", type); }
    }
    private void publishTask(EventType type, Task task) {
        TaskSnapshot snapshot = task.snapshot();
        try { if (messageBus != null) messageBus.publishEvent(AgentEvent.of(snapshot.assignedAgentId() == null ? agentId : snapshot.assignedAgentId(), type, Map.of("taskId", task.getId(), "execution", snapshot))); }
        catch (Exception e) { log.warn("Task observation failed: {}", task.getId()); }
    }
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>(); status.put("agentId", agentId); status.put("state", getState());
        status.put("activePlans", activePlans.size()); status.put("pendingTasks", pendingTasks.size()); status.put("runtime", runtime.metrics());
        status.put("knownAgents", agentDirectory.values().stream().collect(Collectors.toMap(AgentInterface::getAgentId, a -> Map.of("type", a.getAgentType(), "state", a.getState())))); return status;
    }
    public void registerAgent(AgentInterface agent) {
        if (agent instanceof AbstractBaseAgent base) base.attachRuntime(runtime);
        agentDirectory.put(agent.getAgentId(), agent); messageBus.registerAgent(agent);
    }
    @Override public void handleMessage(AgentMessage message) { log.debug("Orchestrator message: {}", message.getType()); }
    @Override public void onEvent(AgentEvent event) {
        if (event.getType() == EventType.AGENT_UNREGISTERED) agentDirectory.remove(event.getAgentId());
        if (event.getType() == EventType.AGENT_REGISTERED) {
            AgentInterface agent = messageBus.getAgent(event.getAgentId());
            if (agent != null && !agentId.equals(agent.getAgentId())) {
                if (agent instanceof AbstractBaseAgent base) base.attachRuntime(runtime); agentDirectory.put(agent.getAgentId(), agent);
            }
        }
    }
    @Override public void initialize(Map<String, Object> config) {}
    @Override public void shutdown() {
        for (var entry : registry.entries()) if (entry.run.outcome() == null) cancel(entry.run.id);
        timer.shutdown(); runtime.close(); agentDirectory.values().forEach(AgentInterface::shutdown); agentDirectory.clear();
        if (messageBus != null) messageBus.unregisterAgent(agentId);
    }
}
