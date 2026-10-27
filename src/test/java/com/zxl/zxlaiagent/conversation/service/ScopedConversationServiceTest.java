package com.zxl.zxlaiagent.conversation.service;

import com.zxl.zxlaiagent.agent.ToolCallAgent;
import com.zxl.zxlaiagent.agent.model.*;
import com.zxl.zxlaiagent.conversation.agent.*;
import com.zxl.zxlaiagent.conversation.model.*;
import com.zxl.zxlaiagent.conversation.repository.ConversationRepository;
import com.zxl.zxlaiagent.security.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** These tests prove ordering/propagation; real PostgreSQL authorization is a separate suite. */
class ScopedConversationServiceTest {
    private static final ActorRef ACTOR = new ActorRef("subject-a", "tenant-a", "session-a", 1);
    private final ConversationRepository repository = mock(ConversationRepository.class);
    private final AuthorizationService authorization = mock(AuthorizationService.class);
    private final ConversationRunGuard guard = new ConversationRunGuard();
    private final DeferredAgent agent = new DeferredAgent();
    private final AtomicReference<ActorRef> providerActor = new AtomicReference<>();
    private final ConversationAgentProvider provider = new ConversationAgentProvider() {
        public AgentDefinition definition() { return new AgentDefinition("safe", "安全", "Safe", "", ""); }
        public ToolCallAgent create() { fail("Formal entry must not call the legacy provider"); return null; }
        public ToolCallAgent create(ActorRef actor, AgentRunOptions options) {
            providerActor.set(actor);
            return agent;
        }
    };
    private final ConversationAgentRegistry registry = new ConversationAgentRegistry(List.of(provider));

    private ConversationService service() {
        var service = new ConversationService(repository, guard, registry);
        service.setAuthorizationService(authorization);
        return service;
    }

    private void allowGates() {
        when(authorization.decide(any(), anyString(), nullable(ResourceRef.class), any(), nullable(String.class)))
                .thenReturn(new AuthorizationDecision("test-decision", "test:1", true, "ALLOWED"));
        doAnswer(call -> ((Supplier<?>) call.getArgument(5)).get()).when(authorization)
                .gate(any(), anyString(), any(), any(), nullable(String.class), any());
    }

    private static Conversation empty(UUID id) {
        Instant now = Instant.now();
        return new Conversation(id, "安全会话", now, now, 0, List.of(), List.of(), "safe");
    }

    @Test void deniedOwnerNeverReadsBodyAndMissingSecurityNeverFallsBack() {
        UUID id = UUID.randomUUID();
        doThrow(new SecurityFailure("RESOURCE_UNAVAILABLE", 403)).when(authorization)
                .gate(eq(ACTOR), eq("conversation.readOwn"), eq(new ResourceRef("conversation", id.toString())),
                        eq(Phase.HISTORY_READ), isNull(), any());
        assertThrows(SecurityFailure.class, () -> service().require(ACTOR, id));
        verifyNoInteractions(repository);
        var unconfigured = new ConversationService(repository, guard, registry);
        assertEquals(503, assertThrows(SecurityFailure.class, () -> unconfigured.require(ACTOR, id)).status());
        verifyNoInteractions(repository);
    }

    @Test void listReadsOnlyIdsFromOwnerIndexAndDoesNotEnumerateForeignFiles() {
        allowGates();
        UUID id = UUID.randomUUID();
        when(authorization.ownedResourceIds(ACTOR, "conversation")).thenReturn(List.of(id.toString()));
        when(repository.find(id)).thenReturn(Optional.of(empty(id)));
        assertEquals(List.of(id), service().list(ACTOR).stream().map(ConversationSummary::id).toList());
        verify(repository, never()).list();
        verify(repository).find(id);
        verifyNoMoreInteractions(repository);
    }

    @Test void creationReservesOwnerBeforeAnyBodyWrite() {
        allowGates();
        var reserved = new AtomicReference<ResourceRef>();
        doAnswer(call -> { reserved.set(call.getArgument(1)); return null; }).when(authorization).registerResource(eq(ACTOR), any());
        when(repository.create(any(UUID.class), eq("新会话"), eq("safe"))).thenAnswer(call -> {
            UUID id = call.getArgument(0);
            assertEquals(new ResourceRef("conversation", id.toString()), reserved.get());
            return empty(id);
        });
        Conversation created = service().create(ACTOR, null, "safe");
        assertEquals(created.id().toString(), reserved.get().id());
        verify(repository, never()).create(anyString(), anyString());
    }

    @Test void aDeniedCreationDoesNotRegisterOrWriteAnything() {
        when(authorization.decide(eq(ACTOR), eq("agent.execute"), isNull(), eq(Phase.ENTRY), isNull()))
                .thenReturn(new AuthorizationDecision("deny", "test:1", false, "ACTION_DENIED"));
        assertThrows(SecurityFailure.class, () -> service().create(ACTOR, "x", "safe"));
        verify(authorization, never()).registerResource(any(), any());
        verifyNoInteractions(repository);
    }

    @Test void deleteDenialReleasesLeaseAndNeverTouchesFile() {
        allowGates();
        UUID id = UUID.randomUUID();
        doThrow(new SecurityFailure("RESOURCE_UNAVAILABLE", 403)).when(authorization)
                .gate(eq(ACTOR), eq("conversation.writeOwn"), any(), eq(Phase.PERSIST), isNull(), any());
        assertThrows(SecurityFailure.class, () -> service().delete(ACTOR, id));
        assertFalse(guard.isBusy(id));
        verifyNoInteractions(repository);
    }

    @Test void foreignBusyConversationDoesNotRevealItsLeaseState() {
        UUID id = UUID.randomUUID();
        when(authorization.decide(eq(ACTOR), eq("conversation.writeOwn"), any(), eq(Phase.ENTRY), isNull()))
                .thenReturn(new AuthorizationDecision("deny", "test:1", false, "RESOURCE_UNAVAILABLE"));
        try (var held = guard.acquire(id)) {
            assertEquals("RESOURCE_UNAVAILABLE", assertThrows(SecurityFailure.class, () -> service().delete(ACTOR, id)).code());
        }
        verifyNoInteractions(repository);
    }

    @Test void scopedProviderMustExplicitlyImplementIdentityPropagation() {
        ConversationAgentProvider legacy = new ConversationAgentProvider() {
            public AgentDefinition definition() { return provider.definition(); }
            public ToolCallAgent create() { throw new AssertionError("Legacy fallback reached"); }
        };
        assertEquals("SCOPED_AGENT_UNAVAILABLE", assertThrows(SecurityFailure.class,
                () -> legacy.create(ACTOR, AgentRunOptions.NONE)).code());
    }

    @Test void completionRechecksAuthorizationAndDenialDoesNotPersistAnswer() {
        allowGates();
        UUID id = UUID.randomUUID();
        AtomicReference<Conversation> stored = new AtomicReference<>(empty(id));
        when(repository.find(id)).thenAnswer(call -> Optional.of(stored.get()));
        doAnswer(call -> { stored.set(call.getArgument(0)); return null; }).when(repository).save(any());
        var mapper = new ConversationMessageMapper();
        var coordinator = new AgentConversationService(service(), repository, guard,
                new ConversationContextWindow(mapper, 8), mapper, registry);
        coordinator.setAuthorizationService(authorization);
        coordinator.chat(ACTOR, "问题", id.toString(), AgentRunOptions.NONE);
        assertSame(ACTOR, providerActor.get());
        assertTrue(guard.isBusy(id));
        verify(authorization).registerResource(eq(ACTOR), argThat(resource -> "run".equals(resource.type())));
        doThrow(new SecurityFailure("UNAUTHENTICATED", 401)).when(authorization)
                .gate(eq(ACTOR), eq("conversation.writeOwn"), any(), eq(Phase.RESULT_PERSIST), anyString(), any());
        assertThrows(SecurityFailure.class, agent::complete);
        assertFalse(guard.isBusy(id));
        verify(repository, times(1)).save(any());
        assertEquals(1, stored.get().messages().size());
        assertEquals("running", stored.get().runs().get(0).status());
    }

    @Test void successfulCompletionCarriesSameActorToPersistence() {
        allowGates();
        UUID id = UUID.randomUUID();
        AtomicReference<Conversation> stored = new AtomicReference<>(empty(id));
        when(repository.find(id)).thenAnswer(call -> Optional.of(stored.get()));
        doAnswer(call -> { stored.set(call.getArgument(0)); return null; }).when(repository).save(any());
        var mapper = new ConversationMessageMapper();
        var coordinator = new AgentConversationService(service(), repository, guard,
                new ConversationContextWindow(mapper, 8), mapper, registry);
        coordinator.setAuthorizationService(authorization);
        coordinator.chat(ACTOR, "问题", id.toString(), AgentRunOptions.NONE);
        agent.complete();
        verify(authorization).gate(eq(ACTOR), eq("conversation.writeOwn"), any(), eq(Phase.RESULT_PERSIST), anyString(), any());
        assertFalse(guard.isBusy(id));
        assertEquals("completed", stored.get().runs().get(0).status());
        assertEquals(2, stored.get().messages().size());
    }

    static class DeferredAgent extends ToolCallAgent {
        DeferredAgent() { super(new ToolCallback[0]); setSystemPrompt("Safe role"); }
        @Override public SseEmitter runStream(String prompt) {
            getMessageList().add(new UserMessage(prompt));
            return new SseEmitter();
        }
        void complete() {
            getMessageList().add(new AssistantMessage("回答"));
            getCompletionListener().accept(new AgentRunSnapshot(getMessageList(), AgentEndReason.ANSWER_READY, null));
        }
    }
}
