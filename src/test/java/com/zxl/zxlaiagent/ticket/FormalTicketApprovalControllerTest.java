package com.zxl.zxlaiagent.ticket;

import com.zxl.zxlaiagent.security.*;
import com.zxl.zxlaiagent.security.web.TrustedHandlerInterceptor;
import com.zxl.zxlaiagent.security.web.WebSecurityAdvice;
import com.zxl.zxlaiagent.security.web.WebSecurityFilter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FormalTicketApprovalControllerTest {
    private static final UUID ID = UUID.fromString("a1a2a3a4-b1b2-c1c2-d1d2-e1e2e3e4e5e6");
    private static final String BASE = "/ai/learning/ticket-approvals/" + ID;
    private static final Instant EXPIRY = Instant.parse("2030-01-02T12:00:00Z");
    private static final TicketCommand COMMAND = new TicketCommand(ID.toString(), "Review this", "Exact draft");
    private static final String BINDING = "\"version\":2,\"operationId\":\"" + ID + "\",\"fingerprint\":\"" + COMMAND.fingerprint() + "\"";
    private static final Cookie SESSION = new Cookie("ZXL_SESSION", "review-session");
    private final TicketApprovalWorkflow workflow = mock(TicketApprovalWorkflow.class);
    private final AuthenticationService auth = mock(AuthenticationService.class);
    private final AuthorizationService authorization = mock(AuthorizationService.class);
    private final AuthenticatedActor reviewer = new AuthenticatedActor("reviewer", "tenant", "session", 1, "ticket-approver", 1);
    private final TicketApproval proposal = TicketApproval.propose(ID,
            new ActorRef("requester", "tenant", "request-session", 1), COMMAND, EXPIRY);
    private MockMvc mvc;

    @BeforeEach
    void configure() {
        mvc = MockMvcBuilders.standaloneSetup(new FormalTicketApprovalController(workflow))
                .setControllerAdvice(new WebSecurityAdvice())
                .addInterceptors(new TrustedHandlerInterceptor(Set.of(FormalTicketApprovalController.class)))
                .addFilters(new WebSecurityFilter(auth, authorization, Set.of()))
                .build();
        when(auth.authenticate("review-session")).thenReturn(reviewer);
        when(authorization.decide(eq(reviewer.ref()), anyString(), isNull(), eq(Phase.ENTRY), isNull()))
                .thenReturn(new AuthorizationDecision("decision", "epoch:1", true, "ALLOWED"));
    }

    @Test
    void reviewerReadsExactProposalWithoutIdentityFields() throws Exception {
        when(workflow.inspect(reviewer.ref(), ID)).thenReturn(proposal);
        mvc.perform(get(BASE).cookie(SESSION)).andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value(COMMAND.title()))
                .andExpect(jsonPath("$.description").value(COMMAND.description()))
                .andExpect(jsonPath("$.expiresAt").value(EXPIRY.toString()))
                .andExpect(jsonPath("$.fingerprint").value(COMMAND.fingerprint()))
                .andExpect(jsonPath("$.operationId").value(ID.toString()))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.outcome").value("PENDING"))
                .andExpect(jsonPath("$.requester").doesNotExist())
                .andExpect(jsonPath("$.reviewer").doesNotExist());
        verify(authorization).decide(reviewer.ref(), "ticket.review", null, Phase.ENTRY, null);
        verify(auth, never()).validateCsrf(any(), any());
    }

    @Test
    void exactMutationRequestsUseSessionIdentityAndDistinctPermissions() throws Exception {
        when(workflow.review(reviewer.ref(), ID, TicketApproval.Outcome.APPROVED, 1, ID.toString(), COMMAND.fingerprint()))
                .thenReturn(proposal.review(reviewer.ref(), TicketApproval.Outcome.APPROVED, 1,
                        ID.toString(), COMMAND.fingerprint(), EXPIRY.minusSeconds(1)));
        when(workflow.execute(reviewer.ref(), ID, 2, ID.toString(), COMMAND.fingerprint()))
                .thenReturn(new Ticket("ticket-1", ID.toString(), COMMAND.title(), COMMAND.description()));
        mvc.perform(post(BASE + "/review").cookie(SESSION).header("X-CSRF-Token", "bound")
                .contentType("application/json").content("{\"decision\":\"APPROVED\"," + BINDING.replace("\":2,", "\":1,") + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.outcome").value("APPROVED"));
        mvc.perform(post(BASE + "/execute").cookie(SESSION).header("X-CSRF-Token", "bound")
                .contentType("application/json").content("{" + BINDING + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value("ticket-1"));
        verify(auth, times(2)).validateCsrf(reviewer.ref(), "bound");
        verify(authorization).decide(reviewer.ref(), "ticket.review", null, Phase.ENTRY, null);
        verify(authorization).decide(reviewer.ref(), "ticket.execute", null, Phase.ENTRY, null);
    }

    @Test
    void invalidBodyAndMissingCsrfCannotReachWorkflow() throws Exception {
        doThrow(new SecurityFailure("CSRF_INVALID", 403)).when(auth).validateCsrf(reviewer.ref(), null);
        mvc.perform(post(BASE + "/execute").cookie(SESSION).contentType("application/json").content("{" + BINDING + "}"))
                .andExpect(status().isForbidden());
        for (String body : Set.of(
                "{\"tenantId\":\"different\"," + BINDING + "}",
                "{\"version\":2," + BINDING + "}",
                "{" + BINDING + "} {}",
                "{" + BINDING.replace("\":2,", "\":2.0,") + "}",
                "{" + BINDING + "}" + " ".repeat(4097))) {
            mvc.perform(post(BASE + "/execute").cookie(SESSION).header("X-CSRF-Token", "bound")
                    .contentType("application/json").content(body)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_APPROVAL_REQUEST"));
        }
        verifyNoInteractions(workflow);
    }

    @Test
    void anonymousDeniedAndUnknownRequestsNeverReachWorkflow() throws Exception {
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        when(authorization.decide(reviewer.ref(), "ticket.review", null, Phase.ENTRY, null))
                .thenReturn(new AuthorizationDecision("decision", "epoch:1", false, "ACTION_FORBIDDEN"));
        mvc.perform(get(BASE).cookie(SESSION)).andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/execute").cookie(SESSION)).andExpect(status().isNotFound());
        verifyNoInteractions(workflow);
    }
}
