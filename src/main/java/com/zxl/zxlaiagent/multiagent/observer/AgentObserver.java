package com.zxl.zxlaiagent.multiagent.observer;

import com.zxl.zxlaiagent.multiagent.bus.AgentMessageBus;
import com.zxl.zxlaiagent.multiagent.model.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

@Component
@Slf4j
public class AgentObserver {

    @Resource
    private AgentMessageBus messageBus;

    private final Map<String, AgentMetrics> agentMetrics = new ConcurrentHashMap<>();
    private final List<AgentEvent> eventLog = new CopyOnWriteArrayList<>();
    private final Map<String, List<Long>> responseTimes = new ConcurrentHashMap<>();
    private final Map<String, Long> taskCounts = new ConcurrentHashMap<>();
    private final Map<String, Long> completedCounts = new ConcurrentHashMap<>();
    private final Map<String, Long> errorCounts = new ConcurrentHashMap<>();

    @Value
    public static class AgentMetrics {
        String agentId;
        String agentType;
        long totalTasks;
        long completedTasks;
        long failedTasks;
        double avgResponseTimeMs;
        long lastActiveTime;
        AgentState currentState;
    }

    @Value
    public static class SystemSnapshot {
        Map<String, AgentMetrics> agents;
        long totalEvents;
        long uptimeMs;
        long totalTasksAcrossAgents;
        long totalErrors;
        Map<String, Long> tasksByType;
    }

    private final long startTime = System.currentTimeMillis();

    @PostConstruct
    public void init() {
        messageBus.addEventListener(EventType.TASK_STARTED, this::onTaskStarted);
        messageBus.addEventListener(EventType.TASK_COMPLETED, this::onTaskCompleted);
        messageBus.addEventListener(EventType.TASK_FAILED, this::onTaskFailed);
        messageBus.addEventListener(EventType.AGENT_REGISTERED, this::onAgentRegistered);
        messageBus.addEventListener(EventType.AGENT_ERROR, this::onAgentError);

        for (EventType type : EventType.values()) messageBus.addEventListener(type, this::recordEvent);

        log.info("AgentObserver initialized");
    }

    public void recordAgentMetric(String agentId, String agentType, AgentState state) {
        agentMetrics.put(agentId, new AgentMetrics(
                agentId, agentType,
                taskCounts.getOrDefault(agentId, 0L),
                completedCounts.getOrDefault(agentId, 0L),
                errorCounts.getOrDefault(agentId, 0L),
                0,
                System.currentTimeMillis(),
                state
        ));
    }

    public void recordResponseTime(String agentId, long ms) {
        responseTimes.computeIfAbsent(agentId, k -> new CopyOnWriteArrayList<>()).add(ms);
    }

    public AgentMetrics getAgentMetrics(String agentId) {
        return agentMetrics.get(agentId);
    }

    public Map<String, AgentMetrics> getAllAgentMetrics() {
        return Map.copyOf(agentMetrics);
    }

    public SystemSnapshot getSystemSnapshot() {
        updateAllMetrics();
        long totalTasks = taskCounts.values().stream().mapToLong(Long::longValue).sum();
        long totalErrors = errorCounts.values().stream().mapToLong(Long::longValue).sum();
        Map<String, Long> byType = agentMetrics.values().stream()
                .collect(Collectors.groupingBy(
                        AgentMetrics::getAgentType,
                        Collectors.summingLong(AgentMetrics::getTotalTasks)));

        return new SystemSnapshot(
                Map.copyOf(agentMetrics),
                (long) eventLog.size(),
                System.currentTimeMillis() - startTime,
                totalTasks,
                totalErrors,
                byType
        );
    }

    public List<AgentEvent> getRecentEvents(int limit) {
        List<AgentEvent> snapshot = List.copyOf(eventLog);
        return snapshot.subList(Math.max(0, snapshot.size() - Math.max(0, limit)), snapshot.size());
    }

    private synchronized void recordEvent(AgentEvent event) {
        eventLog.add(event);
        if (eventLog.size() > 10000) eventLog.remove(0);
    }

    private void onTaskStarted(AgentEvent event) {
        String agentId = event.getAgentId();
        taskCounts.merge(agentId, 1L, Long::sum);
    }

    private void onTaskCompleted(AgentEvent event) {
        String agentId = event.getAgentId();
        completedCounts.merge(agentId, 1L, Long::sum);
        if (event.getData() != null && event.getData().get("execution") instanceof TaskSnapshot snapshot) {
            recordResponseTime(agentId, Math.max(0, snapshot.completedAt() - snapshot.startedAt()));
        }
    }

    private void onTaskFailed(AgentEvent event) {
        String agentId = event.getAgentId();
        errorCounts.merge(agentId, 1L, Long::sum);
    }

    private void onAgentRegistered(AgentEvent event) {
        AgentInterface agent = messageBus.getAgent(event.getAgentId());
        if (agent != null) {
            agentMetrics.put(agent.getAgentId(), new AgentMetrics(
                    agent.getAgentId(), agent.getAgentType(),
                    0, 0, 0, 0, System.currentTimeMillis(), AgentState.IDLE));
        }
    }

    private void onAgentError(AgentEvent event) {
        errorCounts.merge(event.getAgentId(), 1L, Long::sum);
    }

    private void updateAllMetrics() {
        for (var entry : agentMetrics.entrySet()) {
            String agentId = entry.getKey();
            var old = entry.getValue();
            List<Long> times = responseTimes.get(agentId);
            double avgTime = times != null && !times.isEmpty()
                    ? times.stream().mapToLong(Long::longValue).average().orElse(0)
                    : 0;
            agentMetrics.put(agentId, new AgentMetrics(
                    old.getAgentId(), old.getAgentType(),
                    taskCounts.getOrDefault(agentId, 0L),
                    completedCounts.getOrDefault(agentId, 0L),
                    errorCounts.getOrDefault(agentId, 0L),
                    avgTime,
                    old.getLastActiveTime(),
                    old.getCurrentState()
            ));
        }
    }
}
