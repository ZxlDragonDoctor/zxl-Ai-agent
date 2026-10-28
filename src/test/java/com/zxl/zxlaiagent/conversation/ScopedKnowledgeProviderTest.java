package com.zxl.zxlaiagent.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zxl.zxlaiagent.conversation.agent.*;
import com.zxl.zxlaiagent.knowledge.gateway.ScopedKnowledgeClient;
import com.zxl.zxlaiagent.security.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ScopedKnowledgeProviderTest {
    private static final ActorRef ACTOR = new ActorRef("current-subject", "current-tenant", "current-session", 1);
    private static final String RESULT = "{\"status\":\"ANSWERED\",\"answer\":\"Use SSO [E1]\",\"claims\":[],\"evidence\":[]}";
    private KnowledgeAssistantProvider provider(ScopedKnowledgeClient client) throws Exception {
        var provider = new KnowledgeAssistantProvider(URI.create("http://127.0.0.1:18132/answers"), "real");
        provider.getClass().getMethod("setScopedKnowledgeClient", ScopedKnowledgeClient.class).invoke(provider, client);
        return provider;
    }

    @Test void formalCreationFailsClosedWithoutScopedGateway() {
        var provider = new KnowledgeAssistantProvider();
        assertEquals(503, assertThrows(SecurityFailure.class,
                () -> provider.create(ACTOR, AgentRunOptions.NONE)).status());
        assertEquals(401, assertThrows(SecurityFailure.class,
                () -> provider.create(null, AgentRunOptions.NONE)).status());
    }

    @Test void formalStepCarriesActorAndJavaOwnershipButNoIdentityJson() throws Exception {
        var client = mock(ScopedKnowledgeClient.class);
        String conversation = UUID.randomUUID().toString(), run = UUID.randomUUID().toString();
        when(client.exchange(eq(ACTOR), eq("POST"), eq("/ai/learning/knowledge/answers"), isNull(),
                any(byte[].class), any(), any(), anyList(), eq(run))).thenAnswer(call -> {
            var body = new ObjectMapper().readTree((byte[]) call.getArgument(4));
            assertEquals(Set.of("question", "conversationId", "mode", "budgetMs"),
                    new HashSet<>(new ObjectMapper().convertValue(body, Map.class).keySet()));
            assertEquals("real", body.path("mode").asText());
            assertEquals(conversation, body.path("conversationId").asText());
            List<AuthorizationService.Permission> permissions = call.getArgument(7);
            assertTrue(permissions.contains(new AuthorizationService.Permission("agent.execute", null)));
            assertTrue(permissions.contains(new AuthorizationService.Permission("conversation.readOwn", new ResourceRef("conversation", conversation))));
            assertTrue(permissions.contains(new AuthorizationService.Permission("run.readOwn", new ResourceRef("run", run))));
            return new ScopedKnowledgeClient.Response(200, RESULT.getBytes(StandardCharsets.UTF_8));
        });
        var agent = provider(client).create(ACTOR, AgentRunOptions.NONE);
        agent.restoreMessages(List.of(new UserMessage("VPN 401"))); agent.setConversationId(conversation); agent.setRunId(run);
        assertEquals("Use SSO [E1]", agent.step());
        verify(client).exchange(eq(ACTOR), eq("POST"), eq("/ai/learning/knowledge/answers"), isNull(),
                any(byte[].class), any(), any(), anyList(), eq(run));
    }

    @Test void missingExecutionOwnershipNeverStartsGateway() throws Exception {
        var client = mock(ScopedKnowledgeClient.class);
        var agent = provider(client).create(ACTOR, AgentRunOptions.NONE);
        agent.restoreMessages(List.of(new UserMessage("VPN 401")));
        assertThrows(SecurityFailure.class, agent::step);
        verifyNoInteractions(client);
    }

    @Test void revocationFailureIsNotConvertedToGenericModelFailure() throws Exception {
        var client = mock(ScopedKnowledgeClient.class); var denied = new SecurityFailure("UNAUTHENTICATED", 401);
        when(client.exchange(any(), any(), any(), any(), any(), any(), any(), anyList(), any())).thenThrow(denied);
        var agent = provider(client).create(ACTOR, AgentRunOptions.NONE);
        agent.restoreMessages(List.of(new UserMessage("VPN")));
        agent.setConversationId(UUID.randomUUID().toString()); agent.setRunId(UUID.randomUUID().toString());
        assertSame(denied, assertThrows(SecurityFailure.class, agent::step));
    }

    @Test void knowledgeProviderRejectsSkillSelection() throws Exception {
        var provider = provider(mock(ScopedKnowledgeClient.class));
        assertThrows(IllegalArgumentException.class, () -> provider.create(ACTOR, new AgentRunOptions("it-ticket-analysis", "v1")));
    }
}
