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
public class CodeAgent extends AbstractBaseAgent {

    private static final String CODE_SYSTEM_PROMPT = """
            You are an expert coding agent. You can:
            1. Write, analyze, and debug code in Java, Python, JavaScript, and other languages
            2. Read and understand existing codebases
            3. Suggest improvements and refactoring
            4. Write unit tests and integration tests
            5. Generate code documentation
            
            Follow the current task. For schema or rule design, output the requested design only. Do not claim code was executed or deployed. Answer concisely in the user language.
            """;

    private final TaskPromptRenderer renderer;

    public CodeAgent(ChatModel chatModel) {
        this(chatModel, new TaskPromptRenderer(new ObjectMapper()));
    }

    public CodeAgent(ChatModel chatModel, TaskPromptRenderer renderer) {
        super("code-1", "code", chatModel);
        this.renderer = renderer;
        this.capability = AgentCapability.builder()
                .agentType("code")
                .description("Expert coding and software development agent")
                .tools(Set.of("code_generation", "code_review", "debugging", "refactoring", "test_generation"))
                .domains(Set.of("java", "python", "javascript", "spring", "react", "sql"))
                .maxConcurrency(1)
                .canDelegate(false)
                .build();
    }

    @Override
    public CompletableFuture<String> processTask(Task task) {
        return CompletableFuture.supplyAsync(() -> {
            log.info("CodeAgent executing: {}", task.getDescription());
            String result = callLlm(task, CODE_SYSTEM_PROMPT, renderer.render(task));
            log.info("CodeAgent completed: {}", task.getDescription());
            return result;
        });
    }
}
