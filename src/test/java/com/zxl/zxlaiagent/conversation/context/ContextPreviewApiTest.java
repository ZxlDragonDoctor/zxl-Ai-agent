package com.zxl.zxlaiagent.conversation.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zxl.zxlaiagent.agent.ToolCallAgent;
import com.zxl.zxlaiagent.conversation.agent.*;
import com.zxl.zxlaiagent.conversation.repository.FileConversationRepository;
import com.zxl.zxlaiagent.conversation.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ContextPreviewApiTest {
    @TempDir Path directory;
    @Test void previewsRealStoredHistoryWithoutWritesAndHandlesBadRequestAndMissingConversation() throws Exception {
        var objectMapper = new ObjectMapper().findAndRegisterModules();
        var repository = new FileConversationRepository(objectMapper, directory.toString());
        var stored = repository.create("preview", "manus");
        var old = ContextAssemblerTest.history("旧问题", true);
        repository.save(stored.update(stored.title(), old.messages(), old.runs()));
        byte[] before = Files.readAllBytes(directory.resolve(stored.id() + ".json"));
        ConversationAgentProvider provider = new ConversationAgentProvider() {
            public AgentDefinition definition() { return new AgentDefinition("manus", "M", "M", "", ""); }
            public ToolCallAgent create() {
                var agent = new ToolCallAgent(new ToolCallback[0]); agent.setSystemPrompt("system"); return agent;
            }
        };
        var registry = new ConversationAgentRegistry(List.of(provider));
        var guard = new ConversationRunGuard(); var mapper = new ConversationMessageMapper();
        var service = new AgentConversationService(new ConversationService(repository, guard, registry), repository, guard,
                new ConversationContextWindow(mapper, 8), mapper, registry);
        var mvc = MockMvcBuilders.standaloneSetup(new ContextPreviewController(service)).build();
        mvc.perform(post("/ai/learning/context/preview").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("conversationId", stored.id().toString(), "message", "现在"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.budget.estimator").value("utf8-bytes-plus-16-v1"))
                .andExpect(jsonPath("$.budget.selectedRunIds[0]").value(old.runs().get(0).runId().toString()));
        assertArrayEquals(before, Files.readAllBytes(directory.resolve(stored.id() + ".json")));
        mvc.perform(post("/ai/learning/context/preview").contentType(MediaType.APPLICATION_JSON)
                .content("{\"conversationId\":\"bad\",\"message\":\"hello\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/ai/learning/context/preview").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("conversationId", UUID.randomUUID().toString(), "message", "hello"))))
                .andExpect(status().isNotFound());
    }
}
