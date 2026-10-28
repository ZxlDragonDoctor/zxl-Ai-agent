package com.zxl.zxlaiagent.conversation.controller;

import com.zxl.zxlaiagent.conversation.agent.AgentDefinition;
import com.zxl.zxlaiagent.conversation.agent.ConversationAgentRegistry;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "agent.security.mode", havingValue = "legacy-demo", matchIfMissing = true)
public class AgentTypeController {
    private final ConversationAgentRegistry registry;
    public AgentTypeController(ConversationAgentRegistry registry) { this.registry = registry; }
    @GetMapping("/agent-types") public List<AgentDefinition> list() { return registry.list(); }
}
