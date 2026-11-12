package com.zxl.zxlaiagent.security;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class TicketApprovalAuthorizationPgTest {
    @Test
    void onlyDedicatedApproverCanReviewOrExecuteAndRevocationTakesEffect() {
        String tenant = "ticket-" + UUID.randomUUID();
        try (var database = SecurityDatabase.fromEnvironment()) {
            var authentication = new AuthenticationService(database);
            var authorization = new AuthorizationService(database);
            try {
                for (String role : new String[]{"employee", "knowledge-maintainer", "ticket-approver"}) {
                    String id = tenant + "-" + role;
                    authentication.provision(id, id, "synthetic-password", tenant, role);
                    var actor = authentication.login(id, "synthetic-password", tenant).actor().ref();
                    boolean approver = role.equals("ticket-approver");
                    assertTrue(authorization.decide(actor, "ticket.propose", null, Phase.ENTRY, null).allowed());
                    assertEquals(approver, authorization.decide(actor, "ticket.review", null, Phase.ENTRY, null).allowed());
                    assertEquals(approver, authorization.decide(actor, "ticket.execute", null, Phase.PERSIST, null).allowed());
                    assertEquals(role.equals("knowledge-maintainer"),
                            authorization.decide(actor, "knowledge.manage", null, Phase.ENTRY, null).allowed());
                    authentication.revoke(actor);
                    assertEquals("UNAUTHENTICATED",
                            authorization.decide(actor, "ticket.execute", null, Phase.PERSIST, null).code());
                }
            } finally {
                database.tx(connection -> {
                    for (String sql : new String[]{"DELETE FROM authorization_audit WHERE tenant_id=?",
                            "DELETE FROM subject WHERE subject_id LIKE ?", "DELETE FROM tenant WHERE tenant_id=?"}) {
                        try (var statement = SecurityDatabase.statement(connection, sql, sql.contains("LIKE") ? tenant + "-%" : tenant)) {
                            statement.executeUpdate();
                        }
                    }
                    return null;
                });
            }
        }
    }
}
