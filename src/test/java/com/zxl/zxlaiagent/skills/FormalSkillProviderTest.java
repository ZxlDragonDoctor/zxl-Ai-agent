package com.zxl.zxlaiagent.skills;

import com.zxl.zxlaiagent.conversation.agent.*;
import com.zxl.zxlaiagent.security.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import java.util.List;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FormalSkillProviderTest {
    private final ActorRef actor = new ActorRef("subject", "tenant", "session", 1);
    @Test void formalCreationWithoutScopedRunnerFailsClosed() {
        var legacy = mock(SkillRunner.class);
        var provider = new ItTicketSkillProvider(legacy, new SkillRegistry(SkillCatalogue.builtIns()));
        assertFalse(provider.supportsAuthenticatedExecution());
        assertEquals(503, assertThrows(SecurityFailure.class, () -> provider.create(actor, AgentRunOptions.NONE)).status());
        verifyNoInteractions(legacy);
    }
    @Test void unboundFormalAgentCannotExecuteSkill() {
        var scoped = mock(ScopedSkillRunner.class);
        var provider = new ItTicketSkillProvider(mock(SkillRunner.class), new SkillRegistry(SkillCatalogue.formalBuiltIns()));
        provider.setScopedSkillRunner(scoped);
        assertTrue(provider.supportsAuthenticatedExecution());
        var agent = provider.create(actor, new AgentRunOptions("it-ticket-analysis", "v3"));
        agent.setConversationId("conversation"); agent.setRunId("run");
        agent.restoreMessages(List.of(new UserMessage("VPN-401")));
        assertThrows(SecurityFailure.class, agent::step); verifyNoInteractions(scoped);
    }
    @Test void formalCreationRejectsNullActorAndLegacyVersion() {
        var provider = new ItTicketSkillProvider(mock(SkillRunner.class), new SkillRegistry(SkillCatalogue.formalBuiltIns()));
        provider.setScopedSkillRunner(mock(ScopedSkillRunner.class));
        assertEquals(401, assertThrows(SecurityFailure.class, () -> provider.create(null, AgentRunOptions.NONE)).status());
        assertThrows(com.zxl.zxlaiagent.conversation.ConversationException.class,
                () -> provider.create(actor, new AgentRunOptions("it-ticket-analysis", "v1")));
    }
    @Test void boundProviderPinsReviewedVersionAndForwardsTrustedExecutionContext() {
        var registry = new SkillRegistry(SkillCatalogue.formalBuiltIns()); var legacy = mock(SkillRunner.class);
        var scoped = mock(ScopedSkillRunner.class); var provider = new ItTicketSkillProvider(legacy, registry);
        provider.setScopedSkillRunner(scoped);
        var definition = registry.select("it-ticket-analysis", "v3");
        var snapshot = new SkillRunner.Snapshot("run", definition, definition.sha256(), "tool", "executor", "current", 1);
        when(scoped.runWithAuthorizedPublisher(eq(actor), eq("conversation"), eq("run"), eq("it-ticket-analysis"), eq("v3"),
                eq("VPN-401"), any(), any(), any())).thenReturn(new SkillRunner.Result(snapshot, SkillRunner.Status.SUCCEEDED,
                "重新登录 [C1]", new SkillRunner.Draft("ticket-draft-v1", "VPN", "VPN-401", "重新登录 [C1]", null, false, true), List.of(), "OK"));
        var agent = provider.create(actor, AgentRunOptions.NONE);
        registry.setDefault("it-ticket-analysis", "v4");
        agent.bindOperationBoundary(com.zxl.zxlaiagent.security.execution.OperationBoundary.unrestricted());
        agent.setConversationId("conversation"); agent.setRunId("run"); agent.restoreMessages(List.of(new UserMessage("VPN-401")));
        assertTrue(agent.step().contains("尚未创建工单"));
        verify(scoped).runWithAuthorizedPublisher(eq(actor), eq("conversation"), eq("run"), eq("it-ticket-analysis"), eq("v3"),
                eq("VPN-401"), any(), any(), any());
        verifyNoInteractions(legacy);
    }
}
