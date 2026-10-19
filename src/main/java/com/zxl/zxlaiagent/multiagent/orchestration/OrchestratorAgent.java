package com.zxl.zxlaiagent.multiagent.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zxl.zxlaiagent.multiagent.bus.AgentMessageBus;
import com.zxl.zxlaiagent.multiagent.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Slf4j
public class OrchestratorAgent implements AgentInterface {

    private static final String ORCHESTRATOR_SYSTEM_PROMPT = """
            You are a sophisticated orchestrator agent. Your role is to:
            1. Understand high-level user goals
            2. Coordinate multiple specialized agents to work together
            3. Handle task decomposition, assignment, and result aggregation
            4. Make intelligent decisions about delegation and parallelization
            5. Provide a unified response that synthesizes all agent outputs
            """;

    private final String agentId = "orchestrator-1";
    private final String agentType = "orchestrator";
    private final AgentState state = AgentState.IDLE;
    private final AgentCapability capability;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ChatModel chatModel;
    private AgentMessageBus messageBus;

    private final Map<String, Plan> activePlans = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<String>> pendingTasks = new ConcurrentHashMap<>();
    private final ExecutorService taskExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.setName("orchestrator-" + r.hashCode());
        return t;
    });
    private final Map<String, AgentInterface> agentDirectory = new ConcurrentHashMap<>();

    public OrchestratorAgent(ChatModel chatModel) {
        this.chatModel = chatModel;
        this.capability = AgentCapability.builder()
                .agentType("orchestrator")
                .description("Central orchestrator that coordinates all agents")
                .tools(Set.of("delegate", "plan_execution", "result_aggregation", "agent_discovery"))
                .domains(Set.of("orchestration", "coordination", "workflow"))
                .maxConcurrency(10)
                .canDelegate(true)
                .build();
    }

    public void init(AgentMessageBus bus) {
        this.messageBus = bus;
        messageBus.registerAgent(this);
        messageBus.addEventListener(EventType.AGENT_REGISTERED, event -> {
            AgentInterface agent = messageBus.getAgent(event.getAgentId());
            if (agent != null && !agentId.equals(agent.getAgentId())) {
                agentDirectory.put(agent.getAgentId(), agent);
                log.info("Orchestrator discovered agent: {} ({})", agent.getAgentId(), agent.getAgentType());
            }
        });
        log.info("OrchestratorAgent initialized");
    }

    @Override
    public String getAgentId() { return agentId; }

    @Override
    public String getAgentType() { return agentType; }

    @Override
    public AgentState getState() { return state; }

    @Override
    public AgentCapability getCapability() { return capability; }

    @Override
    public CompletableFuture<String> executeTask(Task task) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return orchestrate(task.getDescription(),
                        task.getInput() != null ? task.getInput() : Map.of());
            } catch (Exception e) {
                log.error("Orchestration failed", e);
                throw new CompletionException(e);
            }
        }, taskExecutor);
    }

    public String orchestrate(String goal, Map<String, Object> context) {
        log.info("Orchestrating goal: {}", goal);
        messageBus.publishEvent(AgentEvent.of(agentId, EventType.PLAN_STARTED,
                Map.of("goal", goal)));

        Plan plan = createPlan(goal, context);
        activePlans.put(plan.getId(), plan);
        plan.setStatus(PlanStatus.IN_PROGRESS);

        try {
            executePlan(plan);
            String finalResult = synthesizeResults(plan);
            plan.setStatus(PlanStatus.COMPLETED);
            messageBus.publishEvent(AgentEvent.of(agentId, EventType.PLAN_COMPLETED,
                    Map.of("planId", plan.getId(), "result", finalResult)));
            return finalResult;
        } catch (Exception e) {
            plan.setStatus(PlanStatus.FAILED);
            String errorMessage = e.getMessage() != null
                    ? e.getMessage()
                    : e.getClass().getSimpleName();
            messageBus.publishEvent(AgentEvent.of(agentId, EventType.PLAN_FAILED,
                    Map.of("planId", plan.getId(), "error", errorMessage)));
            throw new RuntimeException("Plan execution failed: " + errorMessage, e);
        } finally {
            activePlans.remove(plan.getId());
        }
    }

    private Plan createPlan(String goal, Map<String, Object> context) {
        AgentInterface planner = findAgentByType("planner");
        if (planner == null) {
            Plan fallback = Plan.create(goal);
            fallback.addTask(Task.create(goal));
            return fallback;
        }
        Task planningTask = Task.create("Decompose: " + goal);
        planningTask.setInput(context);

        try {
            String planJson = planner.executeTask(planningTask).get(30, TimeUnit.SECONDS);
            return objectMapper.readValue(planJson, Plan.class);
        } catch (Exception e) {
            log.warn("Planner unavailable, creating default plan", e);
            Plan fallback = Plan.create(goal);
            fallback.addTask(Task.create(goal));
            return fallback;
        }
    }

    private void executePlan(Plan plan) {
        if (plan.getTasks() == null || plan.getTasks().isEmpty()) {
            throw new IllegalStateException("Plan contains no executable tasks");
        }

        List<Task> completed = new ArrayList<>();
        int maxIterations = plan.getTasks().size() * 2;
        int iterations = 0;

        while (!plan.isComplete() && iterations < maxIterations) {
            iterations++;
            List<Task> readyTasks = plan.getReadyTasks();
            if (readyTasks.isEmpty() && !plan.isComplete()) {
                log.warn("No ready tasks but plan incomplete, checking for blocked tasks");
                markPendingTasksBlocked(plan, "No executable task remains");
                break;
            }
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (Task task : readyTasks) {
                futures.add(executeSingleTask(plan, task));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }

        if (!plan.isComplete() && iterations >= maxIterations) {
            markPendingTasksBlocked(plan, "Plan exceeded the execution iteration limit");
        }

        List<Task> unfinishedTasks = plan.getTasks().stream()
                .filter(task -> task.getStatus() != TaskStatus.COMPLETED)
                .toList();
        if (!unfinishedTasks.isEmpty()) {
            String details = unfinishedTasks.stream()
                    .map(task -> task.getDescription() + " [" + task.getStatus() + "]: " + task.getError())
                    .collect(Collectors.joining("; "));
            throw new IllegalStateException("Plan did not complete: " + details);
        }
    }

    private void markPendingTasksBlocked(Plan plan, String fallbackReason) {
        List<Task> tasks = plan.getTasks();
        for (Task task : tasks) {
            if (task.getStatus() != TaskStatus.PENDING) {
                continue;
            }
            List<String> dependencies = task.getDependencies() == null
                    ? List.of()
                    : task.getDependencies();
            List<String> unresolved = dependencies.stream()
                    .filter(dependencyId -> tasks.stream().noneMatch(candidate ->
                            candidate.getId().equals(dependencyId)
                                    && candidate.getStatus() == TaskStatus.COMPLETED))
                    .toList();
            task.setStatus(TaskStatus.BLOCKED);
            task.setError(unresolved.isEmpty()
                    ? fallbackReason
                    : "Blocked by unresolved dependencies: " + String.join(", ", unresolved));
        }
    }

    private CompletableFuture<Void> executeSingleTask(Plan plan, Task task) {
        return CompletableFuture.runAsync(() -> {
            try {
                String requiredCapability = task.getInput() != null ?
                        (String) task.getInput().getOrDefault("capability", "general") : "general";
                AgentInterface agent = selectBestAgent(task, requiredCapability);

                if (agent != null) {
                    log.info("Assigning task '{}' to agent {}", task.getDescription(), agent.getAgentId());
                    messageBus.sendMessage(AgentMessage.create(agentId, agent.getAgentId(),
                            MessageType.TASK_ASSIGN, task.getDescription()));
                    String result = agent.executeTask(task).get(120, TimeUnit.SECONDS);
                    task.setResult(result);
                    log.info("Task '{}' completed by agent {}", task.getDescription(), agent.getAgentId());
                } else {
                    log.warn("No suitable agent found for task: {}, using fallback", task.getDescription());
                    task.setResult(executeFallback(task));
                }
            } catch (Exception e) {
                task.setStatus(TaskStatus.FAILED);
                String errorMessage = e.getMessage() != null
                        ? e.getMessage()
                        : e.getClass().getSimpleName();
                task.setError(errorMessage);
                log.error("Task '{}' failed: {}", task.getDescription(), errorMessage);
            }
        }, taskExecutor);
    }

    private AgentInterface selectBestAgent(Task task, String capability) {
        return switch (capability.toLowerCase()) {
            case "code" -> findAgentByType("code");
            case "research", "search" -> findAgentByType("research");
            case "browser" -> findAgentByType("browser");
            case "supervisor", "review" -> findAgentByType("supervisor");
            default -> findMostAvailableAgent();
        };
    }

    private AgentInterface findAgentByType(String type) {
        return agentDirectory.values().stream()
                .filter(a -> a.getAgentType().equalsIgnoreCase(type))
                .filter(a -> a.getState() == AgentState.IDLE)
                .findFirst()
                .orElse(null);
    }

    private AgentInterface findMostAvailableAgent() {
        return agentDirectory.values().stream()
                .filter(a -> a.getState() == AgentState.IDLE)
                .findFirst()
                .orElse(null);
    }

    private String executeFallback(Task task) {
        var client = org.springframework.ai.chat.client.ChatClient.builder(chatModel).build();
        return client.prompt()
                .system(ORCHESTRATOR_SYSTEM_PROMPT)
                .user("Task: " + task.getDescription())
                .call()
                .content();
    }

    private String synthesizeResults(Plan plan) {
        String allResults = plan.getTasks().stream()
                .filter(t -> t.getStatus() == TaskStatus.COMPLETED && t.getResult() != null)
                .map(t -> "=== Task: " + t.getDescription() + " ===\n" + t.getResult())
                .collect(Collectors.joining("\n\n"));

        if (plan.getTasks().size() <= 1) return allResults;

        AgentInterface supervisor = findAgentByType("supervisor");
        if (supervisor == null) {
            return allResults;
        }

        try {
            Task synthesisTask = Task.create("Synthesize results for: " + plan.getGoal());
            synthesisTask.setInput(Map.of("plan", plan, "allResults", allResults));
            return supervisor.executeTask(synthesisTask).get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Supervisor synthesis failed, returning raw results", e);
            return allResults;
        }
    }

    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("agentId", agentId);
        status.put("state", state);
        status.put("activePlans", activePlans.size());
        status.put("pendingTasks", pendingTasks.size());
        status.put("knownAgents", agentDirectory.values().stream()
                .collect(Collectors.toMap(
                        AgentInterface::getAgentId,
                        a -> Map.of("type", a.getAgentType(), "state", a.getState()))));
        return status;
    }

    public void registerAgent(AgentInterface agent) {
        agentDirectory.put(agent.getAgentId(), agent);
        messageBus.registerAgent(agent);
    }

    @Override
    public void handleMessage(AgentMessage message) {
        log.debug("Orchestrator received: {} from {}", message.getType(), message.getSourceAgentId());
    }

    @Override
    public void onEvent(AgentEvent event) {
        if (event.getType() == EventType.AGENT_REGISTERED) {
            AgentInterface agent = messageBus.getAgent(event.getAgentId());
            if (agent != null && !agentId.equals(agent.getAgentId())) {
                agentDirectory.put(agent.getAgentId(), agent);
            }
        }
    }

    @Override
    public void initialize(Map<String, Object> config) {}

    @Override
    public void shutdown() {
        agentDirectory.values().forEach(AgentInterface::shutdown);
        agentDirectory.clear();
        messageBus.unregisterAgent(agentId);
    }
}
