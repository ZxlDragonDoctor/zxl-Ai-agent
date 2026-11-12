package com.zxl.zxlaiagent.ticket;

import com.zxl.zxlaiagent.security.ActorRef;
import com.zxl.zxlaiagent.security.AuthenticationService;
import com.zxl.zxlaiagent.security.SecurityDatabase;
import com.zxl.zxlaiagent.security.SecurityFailure;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.zxl.zxlaiagent.security.SecurityDatabase.statement;
import static org.junit.jupiter.api.Assertions.*;

class TicketApprovalStorePgTest {
    @Test void committedReceiptIsAtomicTenantScopedAndSafeToReplay() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String tenant = "ticket-test-" + suffix;
        String author = "author-" + suffix;
        String reviewer = "reviewer-" + suffix;
        var id = UUID.randomUUID();
        var now = Instant.parse("2026-01-01T00:00:00Z");
        var expires = now.plusSeconds(30);
        var command = new TicketCommand("operation", "工单", "保留事务原文");
        try (var db = SecurityDatabase.pooledFromEnvironment()) {
            var auth = new AuthenticationService(db);
            var store = new TicketApprovalStore();
            try {
                auth.provision(author, author, "synthetic-password", tenant, "employee");
                auth.provision(reviewer, reviewer, "synthetic-password", tenant, "employee");
                var requester = auth.login(author, "synthetic-password", tenant).actor().ref();
                var approver = auth.login(reviewer, "synthetic-password", tenant).actor().ref();
                db.tx(c -> {
                    store.insert(c, TicketApproval.propose(id, requester, command, expires));
                    return null;
                });
                assertEquals("APPROVAL_NOT_FOUND", assertThrows(SecurityFailure.class,
                        () -> db.tx(c -> store.lock(c, "different-tenant", id))).code());
                TicketApproval approved = db.tx(c -> {
                    var pending = store.lock(c, tenant, id);
                    var decision = pending.review(approver, TicketApproval.Outcome.APPROVED,
                            1, command.operationId(), command.fingerprint(), now);
                    store.saveDecision(c, decision);
                    return decision;
                });
                assertEquals("APPROVAL_CONFLICT", assertThrows(SecurityFailure.class, () -> db.tx(c -> {
                    store.saveDecision(c, approved);
                    return null;
                })).code());
                assertThrows(IllegalStateException.class, () -> db.tx(c -> {
                    write(store, c, approved, approver, command, now);
                    throw new IllegalStateException("synthetic rollback after insert");
                }));
                assertEquals(0L, count(db, tenant));
                assertEquals("APPROVAL_EXPIRED", assertThrows(SecurityFailure.class,
                        () -> db.tx(c -> write(store, c, approved, approver, command, expires))).code());
                var pool = Executors.newFixedThreadPool(2);
                try {
                    var first = pool.submit(() -> db.tx(c -> write(store, c, approved, approver, command, now)));
                    var second = pool.submit(() -> db.tx(c -> write(store, c, approved, approver, command, now)));
                    var committed = first.get(10, TimeUnit.SECONDS);
                    assertEquals(committed, second.get(10, TimeUnit.SECONDS));
                    assertEquals(committed, db.tx(c -> write(store, c, approved, approver, command, expires)));
                    assertEquals(command.title(), committed.title());
                    assertEquals(command.description(), committed.description());
                } finally { pool.shutdownNow(); }
                assertEquals(1L, count(db, tenant));
            } finally {
                db.tx(c -> {
                    try (var sessions = statement(c, "DELETE FROM lesson17.tenant WHERE tenant_id = ?", tenant)) {
                        sessions.executeUpdate();
                    }
                    try (var subjects = statement(c, "DELETE FROM lesson17.subject WHERE subject_id IN (?, ?)", author, reviewer)) {
                        subjects.executeUpdate();
                    }
                    return null;
                });
            }
        }
    }

    private static Ticket write(TicketApprovalStore store, java.sql.Connection connection, TicketApproval approved,
                                ActorRef approver, TicketCommand command, Instant now) throws java.sql.SQLException {
        return store.writeTicket(connection, approved, approver, 2, command.operationId(), command.fingerprint(), now);
    }

    private static long count(SecurityDatabase db, String tenant) {
        return db.tx(c -> {
            try (var statement = statement(c, "SELECT count(*) FROM lesson17.approved_ticket WHERE tenant_id = ?", tenant);
                 var rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        });
    }
}
