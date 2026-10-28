package com.zxl.zxlaiagent.conversation.controller;

import com.zxl.zxlaiagent.conversation.service.AgentConversationService;
import com.zxl.zxlaiagent.security.web.WebSecurityAdvice;
import com.zxl.zxlaiagent.security.web.WebSecurityFilter;
import com.zxl.zxlaiagent.security.*;
import com.zxl.zxlaiagent.agent.stream.*;
import com.zxl.zxlaiagent.conversation.agent.AgentRunOptions;
import com.zxl.zxlaiagent.conversation.context.ContextAssembler;
import com.zxl.zxlaiagent.conversation.ConversationException;
import jakarta.servlet.http.Cookie;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.test.web.servlet.MockMvc;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.springframework.http.HttpStatus;

class FormalAgentChatControllerTest {
    private static final AuthenticatedActor ACTOR = new AuthenticatedActor("a", "tenant-a", "session-a", 1, "employee", 1);
    private final AgentConversationService service = mock(AgentConversationService.class);
    private final AuthenticationService authentication = mock(AuthenticationService.class);
    private final AuthorizationService authorization = mock(AuthorizationService.class);
    private MockMvc mvc() {
        when(authentication.authenticate("credential-a")).thenReturn(ACTOR);
        doAnswer(call -> {
            if (!"csrf-a".equals(call.getArgument(1))) throw new SecurityFailure("CSRF_INVALID", 403);
            return null;
        }).when(authentication).validateCsrf(any(), nullable(String.class));
        when(authorization.decide(any(), anyString(), nullable(ResourceRef.class), any(), nullable(String.class)))
                .thenReturn(new AuthorizationDecision("decision", "test:1", true, "ALLOWED"));
        return MockMvcBuilders.standaloneSetup(new FormalAgentChatController(service))
                .setControllerAdvice(new WebSecurityAdvice()).addInterceptors(new PreparedAgentStreamInterceptor())
                .addFilters(new WebSecurityFilter(authentication, authorization, Set.of("http://127.0.0.1:5174"))).build();
    }
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder postBody(String path, String body) {
        return post("/api" + path).contextPath("/api").cookie(new Cookie("ZXL_SESSION", "credential-a"))
                .header("X-CSRF-Token", "csrf-a").contentType(MediaType.APPLICATION_JSON).content(body);
    }
    @Test void anonymousFormalPostFailsBeforeServiceEvenWithoutFilter() throws Exception {
        var service = mock(AgentConversationService.class);
        var controller = new FormalAgentChatController(service);
        var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new WebSecurityAdvice()).build();
        mvc.perform(post("/ai/agent/chat").contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"test\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test void authenticatedPostActivatesPreparedStreamThroughRealMvcHookForBothAliases() throws Exception {
        AtomicInteger started = new AtomicInteger();
        when(service.prepareChat(eq(ACTOR.ref()), eq("test"), isNull(), eq(new AgentRunOptions("safe-skill", "v1"))))
                .thenAnswer(call -> {
                    AtomicReference<PreparedAgentStream> ref = new AtomicReference<>();
                    var stream = new PreparedAgentStream(5000, () -> {
                        started.incrementAndGet();
                        try { ref.get().send("live-synthetic-frame"); ref.get().complete(); }
                        catch (Exception error) { throw new IllegalStateException(error); }
                    }, () -> {});
                    ref.set(stream); return stream;
                });
        var mvc = mvc();
        for (String path : List.of("/ai/agent/chat", "/ai/manus/chat")) {
            var result = mvc.perform(postBody(path, "{\"message\":\"test\",\"skillId\":\"safe-skill\",\"skillVersion\":\"v1\"}"))
                    .andExpect(status().isOk()).andExpect(request().asyncStarted()).andReturn();
            assertTrue(result.getResponse().getContentAsString().contains("live-synthetic-frame"));
        }
        assertEquals(2, started.get());
        verify(service, times(2)).prepareChat(ACTOR.ref(), "test", null, new AgentRunOptions("safe-skill", "v1"));
        verify(service, never()).chat(any(ActorRef.class), anyString(), nullable(String.class), any());
        verify(service, never()).chat(anyString(), nullable(String.class), any());
    }

    @Test void anonymousAndMissingCsrfDoNotReachServiceAndGetCannotExecute() throws Exception {
        var mvc = mvc();
        mvc.perform(post("/api/ai/agent/chat").contextPath("/api").contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"test\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/ai/agent/chat").contextPath("/api").cookie(new Cookie("ZXL_SESSION", "credential-a"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"test\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/ai/agent/chat").contextPath("/api").cookie(new Cookie("ZXL_SESSION", "credential-a")))
                .andExpect(status().isNotFound());
        verifyNoInteractions(service);
    }

    @Test void bodyCannotSupplyIdentityOrUnknownFields() throws Exception {
        var mvc = mvc();
        for (String field : List.of("tenantId", "subjectId", "userId", "profile", "actor", "runId", "agentType")) {
            mvc.perform(postBody("/ai/agent/chat", "{\"message\":\"test\",\"" + field + "\":\"forged\"}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_AGENT_REQUEST"));
        }
        verifyNoInteractions(service);
    }

    @Test void malformedDuplicateTrailingOversizedAndWrongTypesAreRejected() throws Exception {
        var mvc = mvc();
        for (String body : List.of("", "[]", "{", "{}", "{\"message\":null}", "{\"message\":1}",
                "{\"message\":\" \"}", "{\"message\":\"a\",\"message\":\"b\"}", "{\"message\":\"a\"} {}",
                "{\"message\":\"a\",\"conversationId\":3}", "{\"message\":\"a\",\"skillId\":{}}",
                "{\"message\":\"" + "a".repeat(12001) + "\"}", " ".repeat(65537),
                "{\"message\":\"a\",\"skillVersion\":\"v1\"}", "{\"message\":\"a\",\"skillId\":\"../escape\"}")) {
            mvc.perform(postBody("/ai/agent/chat", body)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }

    @Test void foreignConversationAndBusinessErrorsUseSafeResponses() throws Exception {
        when(service.prepareChat(eq(ACTOR.ref()), eq("foreign"), anyString(), any()))
                .thenThrow(new SecurityFailure("RESOURCE_UNAVAILABLE", 403));
        when(service.prepareChat(eq(ACTOR.ref()), eq("denied"), anyString(), any()))
                .thenThrow(new SecurityFailure("ACTION_DENIED", 403));
        when(service.prepareChat(eq(ACTOR.ref()), eq("missing"), anyString(), any()))
                .thenThrow(new ConversationException("CONVERSATION_NOT_FOUND", "private-detail", HttpStatus.NOT_FOUND));
        var mvc = mvc();
        for (String message : List.of("foreign", "missing")) {
            mvc.perform(postBody("/ai/agent/chat", "{\"message\":\"" + message + "\",\"conversationId\":\"id\"}"))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
        mvc.perform(postBody("/ai/agent/chat", "{\"message\":\"denied\",\"conversationId\":\"id\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACTION_DENIED"));
    }

    @Test void previewUsesScopedServiceAndReturnsOnlySummaryAndBudget() throws Exception {
        when(service.previewContext(ACTOR.ref(), "id", "test")).thenReturn(new ContextAssembler.Plan(
                List.of(new UserMessage("private-full-history")), new ContextAssembler.Summary("v1", List.of()), null));
        mvc().perform(postBody("/ai/learning/context/preview", "{\"message\":\"test\",\"conversationId\":\"id\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.summary.version").value("v1"))
                .andExpect(jsonPath("$.history").doesNotExist());
        verify(service).previewContext(ACTOR.ref(), "id", "test");
        verify(service, never()).previewContext(anyString(), anyString());
    }

    @Test void previewRejectsMissingConversationUnknownSkillAndForeignResource() throws Exception {
        var mvc = mvc();
        for (String body : List.of("{\"message\":\"test\"}", "{\"message\":\"test\",\"conversationId\":null}",
                "{\"message\":\"test\",\"conversationId\":\"id\",\"skillId\":\"safe-skill\"}")) {
            mvc.perform(postBody("/ai/learning/context/preview", body)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
        when(service.previewContext(ACTOR.ref(), "foreign", "test")).thenThrow(new SecurityFailure("RESOURCE_UNAVAILABLE", 403));
        mvc.perform(postBody("/ai/learning/context/preview", "{\"message\":\"test\",\"conversationId\":\"foreign\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
