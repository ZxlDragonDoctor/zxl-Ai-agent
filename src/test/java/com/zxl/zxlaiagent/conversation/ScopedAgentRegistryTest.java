package com.zxl.zxlaiagent.conversation;

import com.zxl.zxlaiagent.conversation.agent.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScopedAgentRegistryTest {
    @Test void formalDirectoryAdvertisesOnlyProvidersThatHaveScopedExecution() throws Exception {
        ConversationAgentProvider.class.getMethod("supportsAuthenticatedExecution");
        var formal = mock(ConversationAgentProvider.class, call -> switch (call.getMethod().getName()) {
            case "supportsAuthenticatedExecution" -> true;
            case "definition" -> new AgentDefinition("formal", "正式", "Formal", "当前身份", "Current actor");
            default -> null;
        });
        var legacy = mock(ConversationAgentProvider.class, call -> switch (call.getMethod().getName()) {
            case "supportsAuthenticatedExecution" -> false;
            case "definition" -> new AgentDefinition("legacy", "课堂", "Legacy", "固定身份", "Fixed actor");
            default -> null;
        });
        var registry = new ConversationAgentRegistry(List.of(formal, legacy));
        assertEquals(2, registry.list().size());
        var selected = (List<?>) registry.getClass().getMethod("listAuthenticated").invoke(registry);
        assertEquals(List.of(formal.definition()), selected);
    }
}
