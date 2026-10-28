package com.zxl.zxlaiagent.conversation.controller;

import com.zxl.zxlaiagent.conversation.agent.*;
import com.zxl.zxlaiagent.security.*;
import com.zxl.zxlaiagent.security.web.AuthenticatedRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** A formal directory only advertises migrated providers; it does not authorize a later run. */
@RestController
@ConditionalOnProperty(name = "agent.security.mode", havingValue = "required")
public final class FormalAgentTypeController {
    private final ConversationAgentRegistry registry;
    private final AuthorizationService authorization;
    public FormalAgentTypeController(ConversationAgentRegistry registry, AuthorizationService authorization) {
        this.registry = registry; this.authorization = authorization;
    }
    @GetMapping("/agent-types") public List<AgentDefinition> list(HttpServletRequest request) {
        var actor = AuthenticatedRequest.require(request).ref();
        return authorization.gate(actor, "agent.execute", null, Phase.RESULT_PUBLISH, null, registry::listAuthenticated);
    }
}
