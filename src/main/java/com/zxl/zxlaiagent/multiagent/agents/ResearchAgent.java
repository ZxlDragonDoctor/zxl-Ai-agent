package com.zxl.zxlaiagent.multiagent.agents;

import com.zxl.zxlaiagent.multiagent.model.AgentCapability;
import com.zxl.zxlaiagent.multiagent.model.Task;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

@Component
@Slf4j
public class ResearchAgent extends AbstractBaseAgent {

    private static final String RESEARCH_SYSTEM_PROMPT = """
            You are a research and analysis agent. You can:
            1. Search and gather information from the web
            2. Analyze and synthesize information from multiple sources
            3. Generate comprehensive research reports
            4. Compare and contrast different options
            5. Provide data-driven insights and recommendations
            
            Always cite sources and provide balanced analysis.
            """;

    public ResearchAgent(ChatModel chatModel) {
        super("research-1", "research", chatModel);
        this.capability = AgentCapability.builder()
                .agentType("research")
                .description("Information gathering, analysis, and research agent")
                .tools(Set.of("web_search", "web_scrape", "data_analysis", "report_generation"))
                .domains(Set.of("research", "analysis", "data", "writing"))
                .maxConcurrency(2)
                .canDelegate(false)
                .build();
    }

    @Override
    public CompletableFuture<String> processTask(Task task) {
        return CompletableFuture.supplyAsync(() -> {
            log.info("ResearchAgent executing: {}", task.getDescription());
            String context = task.getInput() != null ? task.getInput().toString() : "";
            String result = callLlm(RESEARCH_SYSTEM_PROMPT,
                    "Research Task: " + task.getDescription() + "\nContext: " + context);
            log.info("ResearchAgent completed: {}", task.getDescription());
            return result;
        });
    }
}
