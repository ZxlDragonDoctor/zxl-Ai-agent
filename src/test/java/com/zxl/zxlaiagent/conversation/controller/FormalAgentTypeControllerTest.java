package com.zxl.zxlaiagent.conversation.controller;

import com.zxl.zxlaiagent.conversation.agent.*;
import com.zxl.zxlaiagent.security.*;
import com.zxl.zxlaiagent.security.web.WebSecurityAdvice;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import java.util.function.Supplier;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class FormalAgentTypeControllerTest {
    @Test void anonymousDirectoryCannotEnumerateProviders() throws Exception {
        var registry = mock(ConversationAgentRegistry.class); var authorization = mock(AuthorizationService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new FormalAgentTypeController(registry, authorization))
                .setControllerAdvice(new WebSecurityAdvice()).build();
        mvc.perform(get("/agent-types")).andExpect(status().isUnauthorized());
        verifyNoInteractions(registry, authorization);
    }
    @Test void currentActorGetsOnlyTheFormalDirectoryInsidePublicationGate() throws Exception {
        var registry = mock(ConversationAgentRegistry.class); var authorization = mock(AuthorizationService.class);
        var actor = new AuthenticatedActor("subject", "tenant", "session", 1, "employee", 1);
        when(registry.listAuthenticated()).thenReturn(List.of(new AgentDefinition("enterprise-knowledge", "知识", "Knowledge", "当前身份", "Current actor")));
        when(authorization.gate(eq(actor.ref()), eq("agent.execute"), isNull(), eq(Phase.RESULT_PUBLISH), isNull(), any()))
                .thenAnswer(call -> ((Supplier<?>) call.getArgument(5)).get());
        var mvc = MockMvcBuilders.standaloneSetup(new FormalAgentTypeController(registry, authorization)).build();
        mvc.perform(get("/agent-types").requestAttr("com.zxl.zxlaiagent.security.web.AuthenticatedRequest.actor", actor))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value("enterprise-knowledge"));
        verify(registry).listAuthenticated(); verify(registry, never()).list();
    }
    @Test void revokedActorCannotPublishDirectory() throws Exception {
        var registry = mock(ConversationAgentRegistry.class); var authorization = mock(AuthorizationService.class);
        var actor = new AuthenticatedActor("subject", "tenant", "session", 1, "employee", 1);
        when(authorization.gate(any(), anyString(), isNull(), any(), isNull(), any())).thenThrow(new SecurityFailure("UNAUTHENTICATED", 401));
        var mvc = MockMvcBuilders.standaloneSetup(new FormalAgentTypeController(registry, authorization))
                .setControllerAdvice(new WebSecurityAdvice()).build();
        mvc.perform(get("/agent-types").requestAttr("com.zxl.zxlaiagent.security.web.AuthenticatedRequest.actor", actor))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(registry);
    }
}
