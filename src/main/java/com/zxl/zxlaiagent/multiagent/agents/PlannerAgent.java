package com.zxl.zxlaiagent.multiagent.agents;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zxl.zxlaiagent.multiagent.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;

@Slf4j
public class PlannerAgent extends AbstractBaseAgent {

    private static final String PLANNER_SYSTEM_PROMPT = """
            You are a strategic planning agent. Your job is to:
            1. Analyze complex user requests
            2. Break them down into discrete, executable tasks
            3. Identify dependencies between tasks
            4. Determine the optimal execution order
            
            For each task, specify:
            - task_description: what needs to be done
            - dependencies: list of task indices this depends on (0-indexed, empty list if no deps)
            - required_capability: what type of agent can handle this (code, search, browser, file, general)
            
            Output format: JSON array of task objects
            """;

    private static final String DEPENDENCY_ANALYSIS_PROMPT = """
            Analyze these tasks and determine which ones can be parallelized vs need sequential execution.
            Identify any hidden dependencies between tasks.
            """;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public PlannerAgent(ChatModel chatModel) {
        super("planner-1", "planner", chatModel);
        this.capability = AgentCapability.builder()
                .agentType("planner")
                .description("Strategic task decomposition and planning agent")
                .tools(Set.of("task_decomposition", "dependency_analysis", "scheduling"))
                .domains(Set.of("planning", "scheduling", "analysis"))
                .maxConcurrency(1)
                .canDelegate(true)
                .build();
    }

    @Override
    public CompletableFuture<String> processTask(Task task) {
        return CompletableFuture.supplyAsync(() -> {
            String goal = task.getDescription();
            String context = task.getInput() != null ? task.getInput().toString() : "";
            log.info("PlannerAgent planning for: {}", goal);
            String llmResponse = callLlm(PLANNER_SYSTEM_PROMPT,
                    "Goal: " + goal + "\nContext: " + context);
            try {
                Plan plan = parsePlanFromLlmResponse(llmResponse, goal);
                task.setResult(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(plan));
                messageBus.sendMessage(AgentMessage.create(agentId, "orchestrator-1",
                        MessageType.PLAN_UPDATE, objectMapper.writeValueAsString(plan)));
                return task.getResult();
            } catch (Exception e) {
                log.error("Failed to create plan", e);
                throw new RuntimeException("Planning failed: " + e.getMessage());
            }
        });
    }

    public Plan parsePlanFromLlmResponse(String llmResponse, String goal) {
        String json = extractJsonArray(llmResponse);
        Plan plan = Plan.create(goal);
        try {
            List<Map<String, Object>> taskDefs = objectMapper.readValue(json,
                    new TypeReference<List<Map<String, Object>>>() {});
            for (int i = 0; i < taskDefs.size(); i++) {
                Map<String, Object> def = taskDefs.get(i);
                String desc = (String) def.getOrDefault("task_description", "Task " + i);
                List<String> deps = new ArrayList<>();
                List<?> rawDeps = def.get("dependencies") instanceof List ? (List<?>) def.get("dependencies") : List.of();
                for (Object depIdx : rawDeps) {
                    int idx = ((Number) depIdx).intValue();
                    if (idx < plan.getTasks().size()) {
                        deps.add(plan.getTasks().get(idx).getId());
                    }
                }
                Task t = Task.create(desc, deps);
                t.setInput(Map.of("capability",
                        def.getOrDefault("required_capability", "general")));
                plan.addTask(t);
            }
        } catch (Exception e) {
            log.error("Failed to parse plan JSON, creating default single task", e);
            plan.addTask(Task.create(goal));
        }
        return plan;
    }

    private String extractJsonArray(String text) {
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return "[{\"task_description\":\"" + text.replace("\"", "'") + "\",\"dependencies\":[],\"required_capability\":\"general\"}]";
    }
}
