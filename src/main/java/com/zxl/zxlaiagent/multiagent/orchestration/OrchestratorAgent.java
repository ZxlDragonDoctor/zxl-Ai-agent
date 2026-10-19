package com.zxl.zxlaiagent.multiagent.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
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
    }
    @Override public String getAgentId() { return agentId; }
    @Override public String getAgentType() { return "orchestrator"; }
    @Override public AgentState getState() { return AgentState.IDLE; }
    @Override public AgentCapability getCapability() { return capability; }
    @Override public CompletableFuture<String> executeTask(Task task) {
        return CompletableFuture.supplyAsync(() -> orchestrate(task.getDescription(), task.getInput()), executor);
    }
    public String orchestrate(String goal, Map<String, Object> context) {
        return orchestrate(goal, context, report -> {});
    }
    public String orchestrate(String goal, Map<String, Object> context, Consumer<WorkflowRun.Report> reports) {
        WorkflowRun run = new WorkflowRun(goal, reports);
        run.emit("planning", null, null);
        try {
            if (goal == null || goal.isBlank() || goal.length() > 12000)
                throw new WorkflowException("INVALID_GOAL", "goal", "目标不能为空且不能超过 12000 字符");
            Map<String, Object> userInput = inputs.snapshotUserInput(context);
            publish(EventType.PLAN_STARTED, Map.of("executionId", run.id, "goal", goal));
            run.planningTask.setInput(userInput);
            AgentInterface planner = requireAvailable("planner");
            run.planningTask.setAssignedAgentId(planner.getAgentId());
            String json = await(planner.executeTask(run.planningTask), 60, "planning");
            Plan candidate;
            try { candidate = mapper.readValue(json, Plan.class); }
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
            run.plan.setStatus(PlanStatus.COMPLETED);
            publish(EventType.PLAN_COMPLETED, Map.of("executionId", run.id, "planId", run.plan.getId(), "result", result));
            run.emit("completed", result, null);
            return result;
        } catch (Exception e) {
            log.warn("Workflow {} failed", run.id, e);
            WorkflowError failure = WorkflowError.from(e);
            if (run.plan != null) {
                run.plan.setStatus(PlanStatus.FAILED);
                // 第五课采用遇错停止后续调度策略；精确失败因果留第六课。
                for (Task task : run.plan.getTasks()) if (task.getStatus() == TaskStatus.PENDING) {
                    task.setError("计划已停止后续调度：" + failure.message());
                    task.setErrorCode("PLAN_ABORTED");
                    task.setErrorPath(failure.path());
                    task.setCompletedAt(System.currentTimeMillis());
                    task.setStatus(TaskStatus.BLOCKED);
                }
            }
            publish(EventType.PLAN_FAILED, Map.of("executionId", run.id, "code", failure.code(), "path", failure.path(), "error", failure.message()));
            run.emit("failed", null, failure);
            throw new WorkflowException(failure.code(), failure.path(), failure.message(), e);
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
                task.setAssignedAgentId(worker.getAgentId());
                task.setInput(Map.of("capability", task.getInput().get("capability"), "executionContext", context));
            });
            run.emit("running", null, null);
            wave.forEach((task, worker) -> futures.add(executeSingleTask(task, worker, run)));
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }
    }
    private CompletableFuture<Void> executeSingleTask(Task task, AgentInterface worker, WorkflowRun run) {
        return CompletableFuture.runAsync(() -> {
            try {
                CompletableFuture<String> work = worker.executeTask(task);
                pendingTasks.put(task.getId(), work);
                run.emit("running", null, null);
                String output = await(work, 120, "tasks." + task.getId());
                if (output == null || output.isBlank()) throw new WorkflowException("TASK_OUTPUT_MISSING", "tasks." + task.getId(), "任务缺少输出");
                task.setResult(output);
                task.setCompletedAt(System.currentTimeMillis());
                task.setStatus(TaskStatus.COMPLETED);
            } catch (Exception e) {
                WorkflowError error = WorkflowError.from(e);
                task.setError(error.message());
                task.setErrorCode(error.code());
                task.setErrorPath(error.path());
                task.setCompletedAt(System.currentTimeMillis());
                task.setStatus(TaskStatus.FAILED);
                throw new CompletionException(new WorkflowException(error.code(), error.path(), error.message(), e));
            } finally {
                pendingTasks.remove(task.getId());
                run.emit("running", null, null);
            }
        }, executor);
    }
    private String synthesize(WorkflowRun run, Map<String, Object> userInput) {
        Plan plan = run.plan;
        if (!plan.isComplete()) throw new WorkflowException("PLAN_INCOMPLETE", "tasks", "未完成的计划不能汇总为成功");
        if (plan.getTasks().size() == 1) return plan.getTasks().get(0).getResult();
        Set<String> usedAsDependency = plan.getTasks().stream().flatMap(task -> task.getDependencies().stream()).collect(Collectors.toSet());
        List<Task> sinks = plan.getTasks().stream().filter(task -> !usedAsDependency.contains(task.getId())).toList();
        if (sinks.size() == 1 && "supervisor".equals(sinks.get(0).getInput().get("capability"))) return sinks.get(0).getResult();
        String allResults = plan.getTasks().stream().map(task -> "=== " + task.getDescription() + " ===\n" + task.getResult()).collect(Collectors.joining("\n\n"));
        if (allResults.length() > 100000) throw new WorkflowException("DEPENDENCY_OUTPUT_TOO_LARGE", "synthesis", "汇总内容超过上下文预算");
        // 兼容旧整体汇总入口，额外模型调用单独计数，失败不被原始文本掩盖。
        Task synthesis = Task.create("根据全部成功结果回答原始目标：" + plan.getGoal(),
                plan.getTasks().stream().map(Task::getId).toList());
        run.synthesisTask = synthesis;
        synthesis.setInput(Map.of("plan", plan, "allResults", allResults,
                "capability", "supervisor", "executionContext", inputs.build(plan, synthesis, userInput)));
        AgentInterface worker = requireAvailable("supervisor");
        synthesis.setAssignedAgentId(worker.getAgentId());
        run.emit("synthesizing", null, null);
        CompletableFuture<String> work = worker.executeTask(synthesis);
        run.emit("synthesizing", null, null);
        return await(work, 60, "synthesis");
    }
    private String await(CompletableFuture<String> future, int seconds, String path) {
        try { return future.get(seconds, TimeUnit.SECONDS); }
        catch (TimeoutException e) { throw new WorkflowException("TASK_TIMEOUT", path, "等待 Agent 输出超时", e); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WorkflowException("EXECUTION_INTERRUPTED", path, "执行等待被中断", e);
        } catch (ExecutionException e) {
            WorkflowError error = WorkflowError.from(e);
            throw new WorkflowException(error.code(), error.path(), error.message(), e);
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
        messageBus.publishEvent(AgentEvent.of(agentId, type, data));
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
