package com.zxl.zxlaiagent.multiagent.agents;

import com.zxl.zxlaiagent.multiagent.model.AgentCapability;
import com.zxl.zxlaiagent.multiagent.model.Task;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;


import java.util.Set;
import java.util.concurrent.CompletableFuture;

@Slf4j
public class CodeAgent extends AbstractBaseAgent {

    private static final String CODE_SYSTEM_PROMPT = """
            You are an expert coding agent. You can:
            1. Write, analyze, and debug code in Java, Python, JavaScript, and other languages
            2. Read and understand existing codebases
            3. Suggest improvements and refactoring
            4. Write unit tests and integration tests
            5. Generate code documentation
            
            Always provide complete, working code with proper error handling.
            """;

    public CodeAgent(ChatModel chatModel) {
        super("code-1", "code", chatModel);
        this.capability = AgentCapability.builder()
                .agentType("code")
                .description("Expert coding and software development agent")
                .tools(Set.of("code_generation", "code_review", "debugging", "refactoring", "test_generation"))
                .domains(Set.of("java", "python", "javascript", "spring", "react", "sql"))
                .maxConcurrency(3)
                .canDelegate(false)
                .build();
    }

    @Override
    public CompletableFuture<String> processTask(Task task) {
        return CompletableFuture.supplyAsync(() -> {
            log.info("CodeAgent executing: {}", task.getDescription());
            String context = task.getInput() != null ? task.getInput().toString() : "";
            String result = callLlm(CODE_SYSTEM_PROMPT,
                    "Task: " + task.getDescription() + "\nContext: " + context +
                            "\n\nProvide complete implementation with code blocks.");
            log.info("CodeAgent completed: {}", task.getDescription());
            return result;
        });
    }
}
