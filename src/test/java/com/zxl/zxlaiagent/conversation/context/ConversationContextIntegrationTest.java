package com.zxl.zxlaiagent.conversation.context;

import com.zxl.zxlaiagent.agent.ToolCallAgent;
import com.zxl.zxlaiagent.agent.model.*;
import com.zxl.zxlaiagent.conversation.agent.*;
import com.zxl.zxlaiagent.conversation.model.*;
import com.zxl.zxlaiagent.conversation.repository.ConversationRepository;
import com.zxl.zxlaiagent.conversation.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConversationContextIntegrationTest {
    static class LocalAgent extends ToolCallAgent {
        List<Message> projected;
        LocalAgent(String system) { super(new ToolCallback[0]); setSystemPrompt(system); setNextStepPrompt("next"); }
        @Override public SseEmitter runStream(String prompt) {
            projected = List.copyOf(getMessageList());
            getMessageList().add(new UserMessage(prompt));
            getMessageList().add(new AssistantMessage("本次答复"));
            getCompletionListener().accept(new AgentRunSnapshot(getMessageList(), AgentEndReason.ANSWER_READY, null));
            return new SseEmitter();
        }
    }
    @Test void actualConversationServiceUsesProjectionAndCapturesOnlyCurrentRun() {
        var older = ContextAssemblerTest.history("older question", true);
        var newer = ContextAssemblerTest.history("recent question", true);
        var records = new ArrayList<>(older.messages()); records.addAll(newer.messages());
        var runs = new ArrayList<>(older.runs()); runs.addAll(newer.runs());
        var original = older.update(older.title(), records, runs);
        AtomicReference<Conversation> stored = new AtomicReference<>(original);
        var repo = mock(ConversationRepository.class);
        when(repo.find(original.id())).thenAnswer(inv -> Optional.of(stored.get()));
        doAnswer(inv -> { stored.set(inv.getArgument(0)); return null; }).when(repo).save(any());
        var agent = new LocalAgent("system");
        ConversationAgentProvider provider = new ConversationAgentProvider() {
            public AgentDefinition definition() { return new AgentDefinition("manus", "M", "M", "", ""); }
            public ToolCallAgent create() { return agent; }
        };
        var registry = new ConversationAgentRegistry(List.of(provider));
        var guard = new ConversationRunGuard();
        var mapper = new ConversationMessageMapper();
        var window = new ConversationContextWindow(mapper, 1);
        var service = new AgentConversationService(new ConversationService(repo, guard, registry), repo, guard, window, mapper, registry);
        service.chat("当前问题", original.id().toString());
        assertTrue(agent.projected.get(0).getText().contains(ContextAssembler.TEMPLATE_VERSION));
        assertTrue(agent.projected.stream().noneMatch(m -> m instanceof SystemMessage));
        assertEquals(original.messages().size() + 2, stored.get().messages().size());
        assertEquals(original.messages(), stored.get().messages().subList(0, original.messages().size()));
        assertTrue(stored.get().messages().stream().noneMatch(m -> m.content().contains(ContextAssembler.TEMPLATE_VERSION)));
        assertEquals("当前问题", stored.get().messages().get(original.messages().size()).content());
        assertEquals("completed", stored.get().runs().get(2).status());
        assertFalse(guard.isBusy(original.id()));
    }
    @Test void rejectsProviderSystemOverBudgetBeforeWritingRunAndReleasesLease() {
        var original = ContextAssemblerTest.history("old", true);
        var repo = mock(ConversationRepository.class);
        when(repo.find(original.id())).thenReturn(Optional.of(original));
        var agent = new LocalAgent("系统".repeat(20000));
        ConversationAgentProvider provider = new ConversationAgentProvider() {
            public AgentDefinition definition() { return new AgentDefinition("manus", "M", "M", "", ""); }
            public ToolCallAgent create() { return agent; }
        };
        var registry = new ConversationAgentRegistry(List.of(provider));
        var guard = new ConversationRunGuard(); var mapper = new ConversationMessageMapper();
        var service = new AgentConversationService(new ConversationService(repo, guard, registry), repo, guard,
                new ConversationContextWindow(mapper, 8), mapper, registry);
        assertThrows(com.zxl.zxlaiagent.conversation.ConversationException.class, () -> service.chat("new", original.id().toString()));
        verify(repo, never()).save(any()); assertFalse(guard.isBusy(original.id()));
    }
    @Test void oversizedNewConversationRequestDoesNotPersistAnEmptyConversation() {
        var repo = mock(ConversationRepository.class);
        var created = ContextAssemblerTest.history("old", false);
        when(repo.create(anyString(), anyString())).thenReturn(created);
        when(repo.find(created.id())).thenReturn(Optional.of(created));
        var agent = new LocalAgent("system");
        ConversationAgentProvider provider = new ConversationAgentProvider() {
            public AgentDefinition definition() { return new AgentDefinition("manus", "M", "M", "", ""); }
            public ToolCallAgent create() { return agent; }
        };
        var registry = new ConversationAgentRegistry(List.of(provider));
        var guard = new ConversationRunGuard(); var mapper = new ConversationMessageMapper();
        var service = new AgentConversationService(new ConversationService(repo, guard, registry), repo, guard,
                new ConversationContextWindow(mapper, 8), mapper, registry);
        assertThrows(com.zxl.zxlaiagent.conversation.ConversationException.class, () -> service.chat("中".repeat(12000), null));
        verify(repo, never()).create(anyString(), anyString());
        verify(repo, never()).save(any());
        verify(repo, never()).delete(any());
    }
    @Test void shortNewConversationRequestCreatesAndCapturesRunUsingOneProviderAgent() {
        var repo = mock(ConversationRepository.class);
        var at = java.time.Instant.now();
        var empty = new Conversation(UUID.randomUUID(), "新会话", at, at, 0, List.of(), List.of(), "manus");
        AtomicReference<Conversation> stored = new AtomicReference<>();
        when(repo.create("新会话", "manus")).thenAnswer(inv -> { stored.set(empty); return empty; });
        when(repo.find(empty.id())).thenAnswer(inv -> Optional.ofNullable(stored.get()));
        doAnswer(inv -> { stored.set(inv.getArgument(0)); return null; }).when(repo).save(any());
        var agent = new LocalAgent("system");
        var count = new java.util.concurrent.atomic.AtomicInteger();
        ConversationAgentProvider provider = new ConversationAgentProvider() {
            public AgentDefinition definition() { return new AgentDefinition("manus", "M", "M", "", ""); }
            public ToolCallAgent create() { count.incrementAndGet(); return agent; }
        };
        var registry = new ConversationAgentRegistry(List.of(provider));
        var guard = new ConversationRunGuard(); var mapper = new ConversationMessageMapper();
        var service = new AgentConversationService(new ConversationService(repo, guard, registry), repo, guard,
                new ConversationContextWindow(mapper, 8), mapper, registry);
        assertNotNull(service.chat("新问题", null));
        assertEquals(1, count.get());
        assertEquals("新问题", stored.get().title());
        assertEquals(2, stored.get().messages().size());
        assertEquals("新问题", stored.get().messages().get(0).content());
        assertEquals("completed", stored.get().runs().get(0).status());
        assertFalse(guard.isBusy(empty.id()));
        verify(repo, times(1)).create("新会话", "manus");
    }
}
