package com.zxl.zxlaiagent.multiagent.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.zxl.zxlaiagent.multiagent.bus.AgentMessageBus;
import com.zxl.zxlaiagent.multiagent.execution.*;
import com.zxl.zxlaiagent.multiagent.model.*;
import com.zxl.zxlaiagent.multiagent.planning.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Slf4j
public class OrchestratorAgent implements AgentInterface {
    private final String agentId = "orchestrator-1";
    private final AgentCapability capability = AgentCapability.builder().agentType("orchestrator")
            .description("Validated plan execution and dependency dataflow")
            .tools(Set.of("plan_execution", "agent_discovery"))
            .domains(Set.of("orchestration")).maxConcurrency(10).canDelegate(true).build();
    private final ObjectMapper mapper;
    private final PlanValidator validator;
    private final TaskInputBuilder inputs;
    private AgentMessageBus messageBus;
    private final Map<String, Plan> activePlans = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<String>> pendingTasks = new ConcurrentHashMap<>();
    private final Map<String, AgentInterface> agentDirectory = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "orchestrator-worker");
        thread.setDaemon(true);
        return thread;
    });
    public OrchestratorAgent(ChatModel model) {
        this(model, new ObjectMapper(), new PlanValidator(), new TaskInputBuilder(new ObjectMapper()));
    }
    public OrchestratorAgent(ChatModel model, ObjectMapper mapper, PlanValidator validator, TaskInputBuilder inputs) {
        this.mapper = mapper;
        this.validator = validator;
        this.inputs = inputs;
    }
    public void init(AgentMessageBus bus) {
        messageBus = bus;
        bus.registerAgent(this);
        bus.addEventListener(EventType.AGENT_REGISTERED, this::onEvent);
        bus.addEventListener(EventType.AGENT_UNREGISTERED, this::onEvent);
    }
    @Override public String getAgentId() { return agentId; }
    @Override public String getAgentType() { return "orchestrator"; }
    @Override public AgentState getState() { return AgentState.IDLE; }
    @Override public AgentCapability getCapability() { return capability; }
    @Override public CompletableFuture<String> executeTask(Task task) {
        if (!TaskLifecycle.start(task, agentId).applied())
            return CompletableFuture.failedFuture(new WorkflowException("TASK_ALREADY_STARTED", "tasks." + task.getId(), "任务已开始或结束"));
        return CompletableFuture.supplyAsync(() -> {
            try {
                String result = orchestrate(task.getDescription(), task.getInput());
                TaskLifecycle.complete(task, result);
                return result;
            } catch (Exception e) { TaskLifecycle.fail(task, WorkflowError.from(e)); throw e; }
        }, executor);
    }
    public String orchestrate(String goal, Map<String, Object> context) { return orchestrate(goal, context, report -> {}); }
    public String orchestrate(String goal, Map<String, Object> context, Consumer<WorkflowRun.Report> reports) {
        WorkflowOutcome outcome = run(goal, context, reports);
        if (!outcome.successful()) throw new WorkflowException(outcome.error());
        return outcome.result();
    }
    public WorkflowOutcome run(String goal, Map<String, Object> context, Consumer<WorkflowRun.Report> reports) {
        WorkflowRun run = new WorkflowRun(goal, reports);
        run.emit("planning", null, null);
        try {
            if (goal == null || goal.isBlank() || goal.length() > 12000)
                throw new WorkflowException("INVALID_GOAL", "goal", "目标不能为空且不能超过 12000 字符");
            Map<String, Object> userInput = inputs.snapshotUserInput(context);
            publish(EventType.PLAN_STARTED, Map.of("executionId", run.id, "goal", goal));
            run.planningTask.setInput(userInput);
            AgentInterface planner = requireAvailable("planner");
            String json = executeAndAwait(run.planningTask, planner, 60, "planning", run, "planning");
            Plan candidate;
            try { candidate = mapper.readerFor(Plan.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                    DeserializationFeature.FAIL_ON_IGNORED_PROPERTIES).readValue(json); }
            catch (Exception e) { throw new WorkflowException("INVALID_PLAN_JSON", "planning", "规划角色返回的运行时计划无法读取", e); }
            Set<String> types = agentDirectory.values().stream().map(AgentInterface::getAgentType).collect(Collectors.toSet());
            validator.validate(candidate, types);
            // 原始业务目标由应用持有，不接受 Agent 篡改。
            if (!goal.equals(candidate.getGoal())) throw new WorkflowException("INVALID_PLAN_FIELD", "goal", "规划后的目标与原始目标不一致");
            run.plan = candidate;
            activePlans.put(run.plan.getId(), run.plan);
            run.plan.setStatus(PlanStatus.IN_PROGRESS);
            run.emit("running", null, null);
            executePlan(run, userInput);
            String result = synthesize(run, userInput);
            WorkflowOutcome outcome = run.finishSuccess(result);
            publish(outcome.successful() ? EventType.PLAN_COMPLETED : EventType.PLAN_FAILED,
                    Map.of("executionId", run.id, "outcome", outcome));
            return outcome;
        } catch (Exception e) {
            log.warn("Workflow {} failed", run.id, e);
            WorkflowError failure = FailurePropagation.firstFailure(run.plan, WorkflowError.from(e));
            if (run.plan == null && TaskLifecycle.block(run.planningTask, failure).applied())
                publishTask(EventType.TASK_BLOCKED, run.planningTask);
            for (Task blocked : FailurePropagation.blockPending(run.plan, failure))
                publishTask(EventType.TASK_BLOCKED, blocked);
            WorkflowOutcome outcome = run.finishFailure(failure);
            publish(EventType.PLAN_FAILED, Map.of("executionId", run.id, "outcome", outcome));
            return outcome;
        } finally {
            if (run.plan != null) activePlans.remove(run.plan.getId());
        }
    }
    private void executePlan(WorkflowRun run, Map<String, Object> userInput) {
        Plan plan = run.plan;
        while (!plan.isComplete()) {
            List<Task> ready = plan.getReadyTasks();
            if (ready.isEmpty()) throw new WorkflowException("PLAN_STALLED", "tasks", "计划未完成且没有可执行任务");
            // 同一轮只分配每个工作实例一次。资源等待不会被伪造成业务依赖。
            Set<String> selected = new HashSet<>();
            Map<Task, AgentInterface> wave = new LinkedHashMap<>();
            for (Task task : ready) {
                String type = PlanCapabilities.normalize((String) task.getInput().get("capability"), "tasks." + task.getId() + ".capability");
                AgentInterface worker = available(type, selected);
                if (worker != null) { selected.add(worker.getAgentId()); wave.put(task, worker); }
            }
            if (wave.isEmpty()) throw new WorkflowException("AGENT_BUSY", "agents", "所需工作 Agent 当前忙碌或不可用，请稍后重试");
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            // 先准备整轮输入，再启动业务调用，避免后面的输入失败时已有任务脱离等待范围。
            wave.forEach((task, worker) -> {
                TaskExecutionContext context = inputs.build(plan, task, userInput);
                task.setInput(Map.of("capability", task.getInput().get("capability"), "executionContext", context));
            });
            run.emit("running", null, null);
            wave.forEach((task, worker) -> futures.add(executeSingleTask(task, worker, run)));
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }
    }
    private CompletableFuture<Void> executeSingleTask(Task task, AgentInterface worker, WorkflowRun run) {
        return CompletableFuture.runAsync(() -> {
            try { executeAndAwait(task, worker, 120, "tasks." + task.getId(), run, "running"); }
            finally { run.emit("running", null, null); }
        }, executor);
    }
    private String executeAndAwait(Task task, AgentInterface worker, int seconds, String path, WorkflowRun run, String stage) {
        CompletableFuture<String> work = null;
        try {
            work = worker.executeTask(task);
            if (work == null) throw new WorkflowException("NULL_TASK_FUTURE", path, "Agent 返回了 null Future");
            pendingTasks.put(task.getId(), work);
            CompletableFuture<String> owned = work;
            work.whenComplete((result, error) -> pendingTasks.remove(task.getId(), owned));
            run.emit(stage, null, null);
            String output = await(work, seconds, path);
            TaskSnapshot snapshot = task.snapshot();
            if (snapshot.status() != TaskStatus.COMPLETED || output == null || output.isBlank() || !Objects.equals(snapshot.result(), output))
                throw new WorkflowException("TASK_STATE_MISMATCH", path, "Agent 的 Future 与任务完成快照不一致");
            return snapshot.result();
        } catch (Exception e) {
            WorkflowError error = WorkflowError.from(e).forTask(task.getId());
            TaskLifecycle.Transition failed = TaskLifecycle.fail(task, error);
            if (failed.applied()) publishTask(EventType.TASK_FAILED, task);
            else if (task.getStatus() == TaskStatus.PENDING) {
                TaskLifecycle.Transition blocked = TaskLifecycle.block(task, error);
                if (blocked.applied()) publishTask(EventType.TASK_BLOCKED, task);
            }
            WorkflowError authoritative = task.snapshot().failure() == null ? error : task.snapshot().failure();
            if (path.equals("planning") || path.equals("synthesis")) authoritative = new WorkflowError(authoritative.code(), path,
                    authoritative.message(), authoritative.sourceTaskId(), authoritative.blockedByTaskIds(), authoritative.reason());
            throw new WorkflowException(authoritative, e);
        } finally {
            if (work != null && work.isDone()) pendingTasks.remove(task.getId(), work);
        }
    }
    private String synthesize(WorkflowRun run, Map<String, Object> userInput) {
        Plan plan = run.plan;
        if (!plan.isComplete()) throw new WorkflowException("PLAN_INCOMPLETE", "tasks", "未完成的计划不能汇总为成功");
        if (plan.getTasks().size() == 1) return plan.getTasks().get(0).getResult();
        Set<String> usedAsDependency = plan.getTasks().stream().flatMap(task -> task.getDependencies().stream()).collect(Collectors.toSet());
        List<Task> sinks = plan.getTasks().stream().filter(task -> !usedAsDependency.contains(task.getId())).toList();
        if (sinks.size() == 1 && "supervisor".equals(sinks.get(0).getInput().get("capability"))) return sinks.get(0).getResult();
        Task synthesis = Task.create("根据全部成功结果回答原始目标：" + plan.getGoal(),
                plan.getTasks().stream().map(Task::getId).toList());
        run.synthesisTask = synthesis;
        try {
            String allResults = plan.getTasks().stream().map(task -> "=== " + task.getDescription() + " ===\n" + task.getResult()).collect(Collectors.joining("\n\n"));
            if (allResults.length() > 100000) throw new WorkflowException("DEPENDENCY_OUTPUT_TOO_LARGE", "synthesis", "汇总内容超过上下文预算");
            synthesis.setInput(Map.of("plan", plan, "allResults", allResults,
                    "capability", "supervisor", "executionContext", inputs.build(plan, synthesis, userInput)));
            AgentInterface worker = requireAvailable("supervisor");
            run.emit("synthesizing", null, null);
            return executeAndAwait(synthesis, worker, 60, "synthesis", run, "synthesizing");
        } catch (Exception e) {
            WorkflowError original = WorkflowError.from(e).forTask(synthesis.getId());
            WorkflowError failure = new WorkflowError(original.code(), "synthesis", original.message(), original.sourceTaskId(),
                    original.blockedByTaskIds(), original.code().equals("AGENT_BUSY") ? "RESOURCE_UNAVAILABLE" : original.reason());
            if (TaskLifecycle.block(synthesis, failure).applied()) publishTask(EventType.TASK_BLOCKED, synthesis);
            throw new WorkflowException(failure, e);
        }
    }
    private String await(CompletableFuture<String> future, int seconds, String path) {
        try { return future.get(seconds, TimeUnit.SECONDS); }
        catch (TimeoutException e) { throw new WorkflowException("TASK_TIMEOUT", path, "等待 Agent 输出超时", e); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WorkflowException("EXECUTION_INTERRUPTED", path, "执行等待被中断", e);
        } catch (ExecutionException e) {
            WorkflowError error = WorkflowError.from(e);
            throw new WorkflowException(error, e);
        }
    }
    private AgentInterface available(String type, Set<String> excluded) {
        return agentDirectory.values().stream().filter(agent -> type.equals(agent.getAgentType()))
                .filter(agent -> agent.getState() == AgentState.IDLE && !excluded.contains(agent.getAgentId()))
                .sorted(Comparator.comparing(AgentInterface::getAgentId)).findFirst().orElse(null);
    }
    private AgentInterface requireAvailable(String type) {
        if (agentDirectory.values().stream().noneMatch(agent -> type.equals(agent.getAgentType())))
            throw new WorkflowException("AGENT_TYPE_NOT_REGISTERED", "agents." + type, "工作 Agent 类型尚未注册：" + type);
        AgentInterface worker = available(type, Set.of());
        if (worker == null) throw new WorkflowException("AGENT_BUSY", "agents." + type, "工作 Agent 暂时忙碌或不可用：" + type);
        return worker;
    }
    private void publish(EventType type, Map<String, Object> data) {
        try { messageBus.publishEvent(AgentEvent.of(agentId, type, data)); }
        catch (Exception e) { log.warn("Workflow observation failed: {}", type); }
    }
    private void publishTask(EventType type, Task task) {
        TaskSnapshot snapshot = task.snapshot();
        try {
            messageBus.publishEvent(AgentEvent.of(snapshot.assignedAgentId() == null ? agentId : snapshot.assignedAgentId(),
                    type, Map.of("taskId", task.getId(), "execution", snapshot)));
        } catch (Exception e) { log.warn("Task observation failed: {}", task.getId()); }
    }
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("agentId", agentId);
        status.put("state", getState());
        status.put("activePlans", activePlans.size());
        status.put("pendingTasks", pendingTasks.size());
        status.put("knownAgents", agentDirectory.values().stream().collect(Collectors.toMap(AgentInterface::getAgentId,
                agent -> Map.of("type", agent.getAgentType(), "state", agent.getState()))));
        return status;
    }
    public void registerAgent(AgentInterface agent) { agentDirectory.put(agent.getAgentId(), agent); messageBus.registerAgent(agent); }
    @Override public void handleMessage(AgentMessage message) { log.debug("Orchestrator message: {}", message.getType()); }
    @Override public void onEvent(AgentEvent event) {
        if (event.getType() == EventType.AGENT_UNREGISTERED) agentDirectory.remove(event.getAgentId());
        if (event.getType() == EventType.AGENT_REGISTERED) {
            AgentInterface agent = messageBus.getAgent(event.getAgentId());
            if (agent != null && !agentId.equals(agent.getAgentId())) agentDirectory.put(agent.getAgentId(), agent);
        }
    }
    @Override public void initialize(Map<String, Object> config) {}
    @Override public void shutdown() {
        agentDirectory.values().forEach(AgentInterface::shutdown);
        agentDirectory.clear();
        executor.shutdown();
        messageBus.unregisterAgent(agentId);
    }
}
