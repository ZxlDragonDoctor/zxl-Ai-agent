package com.zxl.zxlaiagent.ticket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zxl.zxlaiagent.agent.model.AgentEndReason;
import com.zxl.zxlaiagent.agent.model.AgentExecutionEvent;
import com.zxl.zxlaiagent.conversation.model.ConversationDetail;
import com.zxl.zxlaiagent.conversation.model.RunRecord;
import com.zxl.zxlaiagent.conversation.service.ConversationService;
import com.zxl.zxlaiagent.security.ActorRef;
import com.zxl.zxlaiagent.security.AuthenticationService;
import com.zxl.zxlaiagent.security.AuthorizationService;
import com.zxl.zxlaiagent.security.ResourceRef;
import com.zxl.zxlaiagent.security.SecurityDatabase;
import com.zxl.zxlaiagent.security.SecurityFailure;
import com.zxl.zxlaiagent.security.web.WebSecurityAdvice;
import com.zxl.zxlaiagent.security.web.WebSecurityFilter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TicketApprovalWorkflowPgTest {
    @Test
    void authorizationAndWriteShareATransactionAndReplayReturnsOneTicket() throws Exception {
        try (var database = SecurityDatabase.fromEnvironment()) {
            String tenant = "workflow-" + UUID.randomUUID();
            String otherTenant = tenant + "-other";
            var auth = new AuthenticationService(database);
            var authorization = new AuthorizationService(database);
            var workflow = new TicketApprovalWorkflow(database, authorization,
                    new TicketApprovalStore(), Clock.systemUTC());
            try {
                var authorLogin = login(auth, tenant, "author", "employee");
                var author = authorLogin.actor().ref();
                var reviewerLogin = login(auth, tenant, "reviewer", "ticket-approver");
                var reviewer = reviewerLogin.actor().ref();
                var foreign = account(auth, otherTenant, "reviewer", "ticket-approver");
                var conversation = UUID.randomUUID();
                var run = UUID.randomUUID();
                authorization.registerResource(author, new ResourceRef("conversation", conversation.toString()));
                authorization.registerResource(author, new ResourceRef("run", run.toString()));
                var draft = new StoredSkillDraft.Draft("VPN 401", "Restore access");
                assertFailure("RESOURCE_UNAVAILABLE", () -> workflow.proposeFromRun(foreign, conversation, run, draft));
                var history = mock(ConversationService.class);
                when(history.detail(author, conversation)).thenReturn(savedSkill(conversation, run));
                var mvc = MockMvcBuilders.standaloneSetup(new FormalTicketApprovalController(workflow),
                                new FormalTicketProposalController(history, workflow, new ObjectMapper()))
                        .setControllerAdvice(new WebSecurityAdvice())
                        .addFilters(new WebSecurityFilter(auth, authorization, Set.of())).build();
                String source = """
                        {"conversationId":"%s","runId":"%s"}
                        """.formatted(conversation, run);
                var authorCookie = new Cookie("ZXL_SESSION", authorLogin.credential());
                mvc.perform(post("/ai/learning/ticket-proposals").cookie(authorCookie)
                                .contentType("application/json").content(source))
                        .andExpect(status().isForbidden());
                verifyNoInteractions(history);
                mvc.perform(post("/ai/learning/ticket-proposals").cookie(authorCookie)
                                .header("X-CSRF-Token", authorLogin.csrfToken())
                                .contentType("application/json").content(source))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.proposalId").value(run.toString()))
                        .andExpect(jsonPath("$.outcome").value("PENDING"));
                var proposal = workflow.inspect(reviewer, run);
                assertEquals(run, proposal.proposalId());
                assertEquals(proposal.expiresAt().toEpochMilli(), workflow.proposeFromRun(author, conversation, run, draft)
                        .expiresAt().toEpochMilli());
                assertFailure("APPROVAL_CONFLICT", () -> workflow.proposeFromRun(author, conversation, run,
                        new StoredSkillDraft.Draft("Changed title", "Restore access")));
                var id = proposal.proposalId();
                var operation = proposal.command().operationId();
                var fingerprint = proposal.command().fingerprint();
                assertFailure("ACTION_DENIED", () -> workflow.inspect(author, id));
                assertFailure("APPROVAL_NOT_FOUND", () -> workflow.inspect(foreign, id));
                var visible = workflow.inspect(reviewer, id);
                assertEquals(proposal.command(), visible.command());
                assertEquals(1, visible.version());
                assertFailure("ACTION_DENIED", () -> workflow.review(author, id, TicketApproval.Outcome.APPROVED, 1, operation, fingerprint));
                assertFailure("APPROVAL_NOT_FOUND", () -> workflow.review(foreign, id, TicketApproval.Outcome.APPROVED, 1, operation, fingerprint));
                assertFailure("APPROVAL_NOT_GRANTED", () -> workflow.execute(reviewer, id, 1, operation, fingerprint));
                var approved = workflow.review(reviewer, id, TicketApproval.Outcome.APPROVED, 1, operation, fingerprint);
                assertEquals(TicketApproval.Outcome.APPROVED,
                        workflow.proposeFromRun(author, conversation, run, draft).outcome());
                assertEquals(approved.version(), workflow.inspect(reviewer, id).version());
                assertFailure("APPROVAL_BINDING_MISMATCH", () -> workflow.execute(reviewer, id, approved.version(), operation, "0".repeat(64)));
                var cookie = new Cookie("ZXL_SESSION", reviewerLogin.credential());
                String path = "/ai/learning/ticket-approvals/" + id;
                mvc.perform(get(path).cookie(cookie)).andExpect(status().isOk())
                        .andExpect(jsonPath("$.title").value(draft.title()))
                        .andExpect(jsonPath("$.version").value(2));
                String request = """
                        {"version":2,"operationId":"%s","fingerprint":"%s"}
                        """.formatted(operation, fingerprint);
                var result = mvc.perform(post(path + "/execute").cookie(cookie)
                                .header("X-CSRF-Token", reviewerLogin.csrfToken())
                                .contentType("application/json").content(request))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
                assertEquals(result, mvc.perform(post(path + "/execute").cookie(cookie)
                                .header("X-CSRF-Token", reviewerLogin.csrfToken())
                                .contentType("application/json").content(request))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
                auth.revoke(reviewer);
                assertFailure("UNAUTHENTICATED", () -> workflow.inspect(reviewer, id));
                assertFailure("UNAUTHENTICATED", () -> workflow.execute(reviewer, id, approved.version(), operation, fingerprint));
                database.tx(connection -> {
                    try (var statement = SecurityDatabase.statement(connection,
                            "SELECT count(*) FROM approved_ticket WHERE tenant_id=?", tenant);
                         var rows = statement.executeQuery()) {
                        assertTrue(rows.next());
                        assertEquals(1, rows.getInt(1));
                    }
                    try (var statement = SecurityDatabase.statement(connection, """
                            SELECT count(*) FROM authorization_audit
                            WHERE tenant_id=? AND run_id=? AND action='ticket.execute' AND code='UNAUTHENTICATED'
                            """, tenant, id.toString());
                         var rows = statement.executeQuery()) {
                        assertTrue(rows.next());
                        assertEquals(1, rows.getInt(1));
                    }
                    return null;
                });
            } finally {
                database.tx(connection -> {
                    for (String sql : new String[]{"DELETE FROM authorization_audit WHERE tenant_id IN (?, ?)",
                            "DELETE FROM subject WHERE subject_id LIKE ? OR subject_id LIKE ?",
                            "DELETE FROM tenant WHERE tenant_id IN (?, ?)"}) {
                        boolean prefix = sql.contains("LIKE");
                        try (var statement = SecurityDatabase.statement(connection, sql,
                                prefix ? tenant + "-%" : tenant, prefix ? otherTenant + "-%" : otherTenant)) {
                            statement.executeUpdate();
                        }
                    }
                    return null;
                });
            }
        }
    }

    private static ActorRef account(AuthenticationService auth, String tenant, String label, String role) {
        return login(auth, tenant, label, role).actor().ref();
    }

    private static ConversationDetail savedSkill(UUID conversation, UUID run) {
        String terminal = """
                {"status":"SUCCEEDED","code":"OK","snapshot":{"runId":"%s","executorVersion":"authenticated-skill-executor-v1",
                  "definition":{"skillId":"it-ticket-analysis","version":"v4","outputSchemaVersion":"ticket-draft-v2"}},
                  "draft":{"schemaVersion":"ticket-draft-v2","title":"VPN 401","problem":"Restore access",
                  "created":false,"requiresApproval":true}}
                """.formatted(run);
        var event = AgentExecutionEvent.of("SKILL_TERMINAL", 4, terminal, "succeeded")
                .withIdentity(conversation.toString(), run.toString());
        var now = Instant.now();
        var record = RunRecord.started(run, now).finish(AgentEndReason.ANSWER_READY, "", null, List.of(event));
        return new ConversationDetail(conversation, "IT", now, now, 1, List.of(), List.of(record), false, "it-ticket-skill");
    }

    private static AuthenticationService.LoginSession login(AuthenticationService auth, String tenant, String label, String role) {
        String id = tenant + "-" + label;
        auth.provision(id, id, "synthetic-password", tenant, role);
        return auth.login(id, "synthetic-password", tenant);
    }

    private static void assertFailure(String expected, Runnable operation) {
        assertEquals(expected, assertThrows(SecurityFailure.class, operation::run).code());
    }
}
