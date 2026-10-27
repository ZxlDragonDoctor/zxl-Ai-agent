package com.zxl.zxlaiagent.conversation.controller;

import com.zxl.zxlaiagent.conversation.service.ConversationService;
import com.zxl.zxlaiagent.security.web.WebSecurityAdvice;
import com.zxl.zxlaiagent.security.web.WebSecurityFilter;
import com.zxl.zxlaiagent.security.*;
import com.zxl.zxlaiagent.conversation.model.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import java.time.Instant;
import java.util.*;

class FormalConversationControllerTest {
    private static final AuthenticatedActor ACTOR_A = new AuthenticatedActor("subject-a", "tenant-a", "session-a", 1, "employee", 1);
    private static final AuthenticatedActor ACTOR_B = new AuthenticatedActor("subject-b", "tenant-b", "session-b", 1, "employee", 1);
    private final ConversationService service = mock(ConversationService.class);
    private final AuthenticationService authentication = mock(AuthenticationService.class);
    private final AuthorizationService authorization = mock(AuthorizationService.class);

    private MockMvc mvc() {
        when(authentication.authenticate("credential-a")).thenReturn(ACTOR_A);
        when(authentication.authenticate("credential-b")).thenReturn(ACTOR_B);
        doAnswer(call -> {
            ActorRef actor = call.getArgument(0);
            String csrf = call.getArgument(1);
            if (!("csrf-" + (actor.equals(ACTOR_A.ref()) ? "a" : "b")).equals(csrf)) throw new SecurityFailure("CSRF_INVALID", 403);
            return null;
        }).when(authentication).validateCsrf(any(), nullable(String.class));
        when(authorization.decide(any(), anyString(), nullable(ResourceRef.class), any(), nullable(String.class)))
                .thenReturn(new AuthorizationDecision("decision", "test:1", true, "ALLOWED"));
        return MockMvcBuilders.standaloneSetup(new FormalConversationController(service))
                .setControllerAdvice(new WebSecurityAdvice())
                .addFilters(new WebSecurityFilter(authentication, authorization, Set.of("http://127.0.0.1:5174")))
                .build();
    }

    private static Conversation conversation(UUID id) {
        Instant now = Instant.now();
        return new Conversation(id, "Owned", now, now, 0, List.of(), List.of(), "safe");
    }
    @Test void formalEntryRequiresTrustedRequestEvenWhenFilterWasOmitted() throws Exception {
        ConversationService service = mock(ConversationService.class);
        Object controller;
        try {
            controller = Class.forName("com.zxl.zxlaiagent.conversation.controller.FormalConversationController")
                    .getConstructor(ConversationService.class).newInstance(service);
        } catch (ClassNotFoundException missingFormalEntry) {
            controller = new ConversationController(service);
        }
        var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new WebSecurityAdvice()).build();
        mvc.perform(get("/conversations")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test void anonymousRequestDoesNotReachService() throws Exception {
        mvc().perform(get("/api/conversations").contextPath("/api"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        verifyNoInteractions(service);
    }

    @Test void listUsesAuthenticatedActorAndNeverLegacyList() throws Exception {
        when(service.list(ACTOR_A.ref())).thenReturn(List.of());
        mvc().perform(get("/api/conversations").contextPath("/api").cookie(new Cookie("ZXL_SESSION", "credential-a")))
                .andExpect(status().isOk());
        verify(service).list(ACTOR_A.ref());
        verify(service, never()).list();
    }

    @Test void filterDeniesForeignOwnerBeforeControllerAndHidesResource() throws Exception {
        UUID id = UUID.randomUUID();
        MockMvc mvc = mvc();
        when(authorization.decide(eq(ACTOR_B.ref()), eq("conversation.readOwn"), eq(new ResourceRef("conversation", id.toString())), eq(Phase.ENTRY), isNull()))
                .thenReturn(new AuthorizationDecision("deny", "test:1", false, "RESOURCE_UNAVAILABLE"));
        mvc.perform(get("/api/conversations/" + id).contextPath("/api").cookie(new Cookie("ZXL_SESSION", "credential-b")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        verifyNoInteractions(service);
    }

    @Test void serviceOwnerRecheckAlsoMapsForeignResourceTo404() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.detail(ACTOR_B.ref(), id)).thenThrow(new SecurityFailure("RESOURCE_UNAVAILABLE", 403));
        mvc().perform(get("/api/conversations/" + id).contextPath("/api").cookie(new Cookie("ZXL_SESSION", "credential-b")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        verify(service, never()).detail(id);
    }

    @Test void ownedDetailIsReadThroughScopedService() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.detail(ACTOR_A.ref(), id)).thenReturn(ConversationDetail.of(conversation(id), false));
        mvc().perform(get("/api/conversations/" + id).contextPath("/api").cookie(new Cookie("ZXL_SESSION", "credential-a")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id.toString()));
        verify(service).detail(ACTOR_A.ref(), id);
        verify(service, never()).detail(id);
    }

    @Test void mutationWithoutCsrfNeverCreatesConversation() throws Exception {
        mvc().perform(post("/api/conversations").contextPath("/api").cookie(new Cookie("ZXL_SESSION", "credential-a"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"x\",\"agentType\":\"safe\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        verifyNoInteractions(service);
    }

    @Test void creationAndDetailUseSameTrustedActor() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.create(ACTOR_A.ref(), "x", "safe")).thenReturn(conversation(id));
        when(service.detail(ACTOR_A.ref(), id)).thenReturn(ConversationDetail.of(conversation(id), false));
        mvc().perform(post("/api/conversations").contextPath("/api").cookie(new Cookie("ZXL_SESSION", "credential-a"))
                        .header("X-CSRF-Token", "csrf-a").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\",\"agentType\":\"safe\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(id.toString()));
        verify(service).create(ACTOR_A.ref(), "x", "safe");
        verify(service).detail(ACTOR_A.ref(), id);
        verify(service, never()).create(anyString(), anyString());
    }

    @Test void bodyIdentityFieldsCannotSelectAnotherTenantOrSubject() throws Exception {
        MockMvc mvc = mvc();
        for (String field : List.of("tenantId", "subjectId", "userId", "profile", "actor")) {
            mvc.perform(post("/api/conversations").contextPath("/api").cookie(new Cookie("ZXL_SESSION", "credential-a"))
                            .header("X-CSRF-Token", "csrf-a").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"x\",\"" + field + "\":\"forged\"}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CONVERSATION_REQUEST"));
        }
        verifyNoInteractions(service);
    }

    @Test void malformedDuplicateTrailingAndOversizedBodiesAreRejected() throws Exception {
        MockMvc mvc = mvc();
        for (String body : List.of("[]", "null", "{\"title\":1}", "{\"title\":\"x\",\"title\":\"y\"}",
                "{} {}", "{", "{\"title\":\"" + "x".repeat(4100) + "\"}")) {
            mvc.perform(post("/api/conversations").contextPath("/api").cookie(new Cookie("ZXL_SESSION", "credential-a"))
                            .header("X-CSRF-Token", "csrf-a").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }

    @Test void deleteRequiresCsrfAndCallsScopedMethod() throws Exception {
        UUID id = UUID.randomUUID();
        MockMvc mvc = mvc();
        mvc.perform(delete("/api/conversations/" + id).contextPath("/api").cookie(new Cookie("ZXL_SESSION", "credential-a")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
        mvc.perform(delete("/api/conversations/" + id).contextPath("/api").cookie(new Cookie("ZXL_SESSION", "credential-a"))
                        .header("X-CSRF-Token", "csrf-a"))
                .andExpect(status().isNoContent());
        verify(service).delete(ACTOR_A.ref(), id);
        verify(service, never()).delete(id);
    }

    @Test void formalAndLegacyControllersAreMutuallyExclusive() {
        var runner = new ApplicationContextRunner().withBean(ConversationService.class, () -> service)
                .withUserConfiguration(ConversationController.class, FormalConversationController.class);
        runner.withPropertyValues("agent.security.mode=required").run(context -> {
            assertEquals(1, context.getBeansOfType(FormalConversationController.class).size());
            assertTrue(context.getBeansOfType(ConversationController.class).isEmpty());
        });
        runner.withPropertyValues("agent.security.mode=legacy-demo").run(context -> {
            assertTrue(context.getBeansOfType(FormalConversationController.class).isEmpty());
            assertEquals(1, context.getBeansOfType(ConversationController.class).size());
        });
        runner.run(context -> {
            assertTrue(context.getBeansOfType(FormalConversationController.class).isEmpty());
            assertEquals(1, context.getBeansOfType(ConversationController.class).size());
        });
    }
}
