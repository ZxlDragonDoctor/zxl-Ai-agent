package com.zxl.zxlaiagent.ticket;

import com.zxl.zxlaiagent.security.ActorRef;
import com.zxl.zxlaiagent.security.AuthenticationService;
import com.zxl.zxlaiagent.security.AuthorizationService;
import com.zxl.zxlaiagent.security.SecurityDatabase;
import com.zxl.zxlaiagent.security.SecurityFailure;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TicketApprovalWorkflowPgTest {
    @Test
    void authorizationAndWriteShareATransactionAndReplayReturnsOneTicket() {
        try (var database = SecurityDatabase.fromEnvironment()) {
            String tenant = "workflow-" + UUID.randomUUID();
            String otherTenant = tenant + "-other";
            var auth = new AuthenticationService(database);
            var workflow = new TicketApprovalWorkflow(database, new AuthorizationService(database),
                    new TicketApprovalStore(), Clock.systemUTC());
            try {
                var author = account(auth, tenant, "author", "employee");
                var reviewer = account(auth, tenant, "reviewer", "ticket-approver");
                var foreign = account(auth, otherTenant, "reviewer", "ticket-approver");
                var proposal = workflow.propose(author, "VPN 401", "Restore access");
                var id = proposal.proposalId();
                var operation = proposal.command().operationId();
                var fingerprint = proposal.command().fingerprint();
                assertFailure("ACTION_DENIED", () -> workflow.review(author, id, TicketApproval.Outcome.APPROVED, 1, operation, fingerprint));
                assertFailure("APPROVAL_NOT_FOUND", () -> workflow.review(foreign, id, TicketApproval.Outcome.APPROVED, 1, operation, fingerprint));
                assertFailure("APPROVAL_NOT_GRANTED", () -> workflow.execute(reviewer, id, 1, operation, fingerprint));
                var approved = workflow.review(reviewer, id, TicketApproval.Outcome.APPROVED, 1, operation, fingerprint);
                assertFailure("APPROVAL_BINDING_MISMATCH", () -> workflow.execute(reviewer, id, approved.version(), operation, "0".repeat(64)));
                var receipt = workflow.execute(reviewer, id, approved.version(), operation, fingerprint);
                assertEquals(receipt, workflow.execute(reviewer, id, approved.version(), operation, fingerprint));
                auth.revoke(reviewer);
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
        String id = tenant + "-" + label;
        auth.provision(id, id, "synthetic-password", tenant, role);
        return auth.login(id, "synthetic-password", tenant).actor().ref();
    }

    private static void assertFailure(String expected, Runnable operation) {
        assertEquals(expected, assertThrows(SecurityFailure.class, operation::run).code());
    }
}
