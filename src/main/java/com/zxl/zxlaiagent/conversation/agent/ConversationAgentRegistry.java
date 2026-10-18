package com.zxl.zxlaiagent.conversation.agent;

import com.zxl.zxlaiagent.conversation.ConversationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class ConversationAgentRegistry {
    private final Map<String, ConversationAgentProvider> providers = new LinkedHashMap<>();

    public ConversationAgentRegistry(List<ConversationAgentProvider> providers) {
        for (var provider : providers) {
            if (this.providers.putIfAbsent(provider.definition().id(), provider) != null) {
                throw new IllegalStateException("Agent 类型重复: " + provider.definition().id());
            }
        }
    }

    public ConversationAgentProvider require(String type) {
        var provider = providers.get(type);
        if (provider == null) throw new ConversationException("INVALID_AGENT_TYPE", "不支持的 Agent 类型", HttpStatus.BAD_REQUEST);
        return provider;
    }

    public List<AgentDefinition> list() { return providers.values().stream().map(ConversationAgentProvider::definition).toList(); }
}
