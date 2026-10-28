package com.zxl.zxlaiagent.conversation;

import com.zxl.zxlaiagent.conversation.agent.*;
import com.zxl.zxlaiagent.security.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScopedLoveProviderTest {
    @Test void authenticatedRoleProviderUsesNoToolsAndRequiresExplicitActor() {
        var provider = new LoveAssistantProvider(null);
        var actor = new ActorRef("subject", "tenant", "session", 1);
        var agent = assertDoesNotThrow(() -> provider.create(actor, AgentRunOptions.NONE));
        assertEquals("LoveAssistant", agent.getName());
        assertThrows(SecurityFailure.class, () -> provider.create(null, AgentRunOptions.NONE));
    }

    @Test void roleProviderRejectsSkillSelectionInsteadOfIgnoringIt() {
        var provider = new LoveAssistantProvider(null);
        assertThrows(IllegalArgumentException.class, () -> provider.create(
                new ActorRef("subject", "tenant", "session", 1), new AgentRunOptions("it-ticket-analysis", "v1")));
    }
}
