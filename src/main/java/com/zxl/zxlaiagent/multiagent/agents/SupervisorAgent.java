package com.zxl.zxlaiagent.multiagent.agents;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zxl.zxlaiagent.multiagent.execution.TaskPromptRenderer;
import com.zxl.zxlaiagent.multiagent.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@Slf4j
public class SupervisorAgent extends AbstractBaseAgent {

    private static final String SUPERVISOR_SYSTEM_PROMPT = """
            You are a supervisor agent responsible for quality control and oversight.
            Your responsibilities:
            1. Review task results from other agents for quality and correctness
            2. Identify issues, gaps, or errors in completed work
            3. Provide constructive feedback for improvement
            4. Approve task completion or request rework
            5. Synthesize results from multiple agents into coherent output
            
            Use actual upstream outputs. Answer concisely in the user language. Do not claim tickets were created or actions were executed. Explicitly identify required human confirmation.
            """;

    private final TaskPromptRenderer renderer;

    public SupervisorAgent(ChatModel chatModel) {
        this(chatModel, new TaskPromptRenderer(new ObjectMapper()));
    }
    public SupervisorAgent(ChatModel chatModel, TaskPromptRenderer renderer) {
        super("supervisor-1", "supervisor", chatModel);
        this.renderer = renderer;
        this.capability = AgentCapability.builder()
                .agentType("supervisor")
                .description("Quality control, oversight, and result synthesis agent")
                .tools(Set.of("review", "approve", "feedback", "synthesis"))
                .domains(Set.of("qa", "review", "oversight"))
                .maxConcurrency(1)
                .canDelegate(true)
                .build();
    }

    @Override
    public CompletableFuture<String> processTask(Task task) {
        return CompletableFuture.supplyAsync(() -> {
            log.info("SupervisorAgent reviewing task: {}", task.getId());

            Plan plan = null;
            String allResults = "";
            if (task.getInput() != null) {
                Object planObj = task.getInput().get("plan");
                if (planObj instanceof Plan p) plan = p;
                Object resultsObj = task.getInput().get("allResults");
                if (resultsObj instanceof String s) allResults = s;
            }

            String reviewContext = task.getInput() != null && task.getInput().containsKey("executionContext")
                    ? renderer.render(task)
                    : "Current task: " + task.getDescription() + "\n" + buildReviewContext(plan, allResults);
            String review = callLlm(task, SUPERVISOR_SYSTEM_PROMPT,
                    "Review the following work and provide a final synthesized result:\n\n" + reviewContext);

            log.info("SupervisorAgent completed review for task: {}", task.getId());
            return review;
        });
    }

    private String buildReviewContext(Plan plan, String allResults) {
        StringBuilder sb = new StringBuilder();
        if (plan != null) {
            sb.append("Plan Goal: ").append(plan.getGoal()).append("\n");
            sb.append("Tasks: ").append(plan.getTasks().size()).append("\n\n");
        }
        sb.append("Results:\n").append(allResults);
        return sb.toString();
    }

    @Override
    protected void handleQuery(AgentMessage message) {
        AgentMessage response = AgentMessage.create(agentId, message.getSourceAgentId(),
                MessageType.RESPONSE, "Supervisor available. Active reviews: pending");
        messageBus.sendMessage(response);
    }
}
