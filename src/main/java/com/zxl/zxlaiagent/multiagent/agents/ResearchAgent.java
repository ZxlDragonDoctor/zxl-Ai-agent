package com.zxl.zxlaiagent.multiagent.agents;

import com.zxl.zxlaiagent.multiagent.model.AgentCapability;
import com.zxl.zxlaiagent.multiagent.model.Task;
import com.zxl.zxlaiagent.multiagent.execution.TaskPromptRenderer;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;


import java.util.Set;
import java.util.concurrent.CompletableFuture;

@Slf4j
public class ResearchAgent extends AbstractBaseAgent {

    private static final String RESEARCH_SYSTEM_PROMPT = """
            You are a research and analysis agent. You can:
            1. Analyze only the material provided in the execution context
            2. Analyze and synthesize information from multiple sources
            3. Generate comprehensive research reports
            4. Compare and contrast different options
            5. Provide data-driven insights and recommendations
            
            Do not invent sources, web searches or tool calls. Mark missing evidence explicitly. Answer concisely in the user language.
            """;

    private final TaskPromptRenderer renderer;

    public ResearchAgent(ChatModel chatModel) {
        this(chatModel, new TaskPromptRenderer(new ObjectMapper()));
    }

    public ResearchAgent(ChatModel chatModel, TaskPromptRenderer renderer) {
        super("research-1", "research", chatModel);
        this.renderer = renderer;
        this.capability = AgentCapability.builder()
                .agentType("research")
                .description("Information gathering, analysis, and research agent")
                .tools(Set.of("data_analysis", "report_generation"))
                .domains(Set.of("research", "analysis", "data", "writing"))
                .maxConcurrency(1)
                .canDelegate(false)
                .build();
    }

    @Override
    public CompletableFuture<String> processTask(Task task) {
        return async(task, () -> {
            log.info("ResearchAgent executing: {}", task.getDescription());
            String result = callLlm(task, RESEARCH_SYSTEM_PROMPT, renderer.render(task));
            log.info("ResearchAgent completed: {}", task.getDescription());
            return result;
        });
    }
}
