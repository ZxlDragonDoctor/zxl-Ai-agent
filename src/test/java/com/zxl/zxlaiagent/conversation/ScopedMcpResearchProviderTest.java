package com.zxl.zxlaiagent.conversation;

import com.zxl.zxlaiagent.conversation.agent.*;
import com.zxl.zxlaiagent.research.*;
import com.zxl.zxlaiagent.security.*;
import com.zxl.zxlaiagent.security.execution.OperationBoundary;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ScopedMcpResearchProviderTest {
    private static final ActorRef ACTOR = new ActorRef("subject", "tenant", "session", 1);
    private McpResearchProvider provider(ScopedResearchFactory factory) throws Exception {
        var provider = new McpResearchProvider(mock(ResearchRunner.class));
        provider.getClass().getMethod("setScopedResearchFactory", ScopedResearchFactory.class).invoke(provider, factory);
        return provider;
    }
    @Test void formalCreationNeverFallsBackToLegacyRunner() {
        var legacy = mock(ResearchRunner.class); var provider = new McpResearchProvider(legacy);
        assertEquals(503, assertThrows(SecurityFailure.class, () -> provider.create(ACTOR, AgentRunOptions.NONE)).status());
        assertEquals(401, assertThrows(SecurityFailure.class, () -> provider.create(null, AgentRunOptions.NONE)).status());
        verifyNoInteractions(legacy);
    }
    @Test void formalExecutionPassesCurrentActorAndOwnershipToFactory() throws Exception {
        var factory = mock(ScopedResearchFactory.class); var runner = mock(ResearchRunner.class);
        String conversation = UUID.randomUUID().toString(), run = UUID.randomUUID().toString();
        when(factory.create(eq(ACTOR), eq(run), anyList())).thenAnswer(call -> {
            List<AuthorizationService.Permission> requirements = call.getArgument(2);
            assertTrue(requirements.contains(new AuthorizationService.Permission("conversation.readOwn", new ResourceRef("conversation", conversation))));
            assertTrue(requirements.contains(new AuthorizationService.Permission("run.readOwn", new ResourceRef("run", run))));
            return runner;
        });
        when(runner.runWithAuthorizedPublisher(eq("VPN"), any(), any(), any())).thenReturn("SSO [C1]");
        var agent = provider(factory).create(ACTOR, AgentRunOptions.NONE);
        agent.bindOperationBoundary(OperationBoundary.unrestricted());
        agent.setConversationId(conversation); agent.setRunId(run); agent.restoreMessages(List.of(new UserMessage("VPN")));
        assertEquals("SSO [C1]", agent.step());
        verify(runner).runWithAuthorizedPublisher(eq("VPN"), any(), any(), any());
        verify(runner, never()).run(any(), any(), any(), any());
    }
    @Test void unboundAgentNeverCreatesResearchExecution() throws Exception {
        var factory = mock(ScopedResearchFactory.class);
        var agent = provider(factory).create(ACTOR, AgentRunOptions.NONE);
        agent.setConversationId(UUID.randomUUID().toString()); agent.setRunId(UUID.randomUUID().toString());
        agent.restoreMessages(List.of(new UserMessage("VPN")));
        assertThrows(SecurityFailure.class, agent::step); verifyNoInteractions(factory);
    }
    @Test void unrelatedSkillSelectionIsRejected() throws Exception {
        var provider = provider(mock(ScopedResearchFactory.class));
        assertThrows(IllegalArgumentException.class, () -> provider.create(ACTOR, new AgentRunOptions("it-ticket-analysis", "v1")));
    }
}
