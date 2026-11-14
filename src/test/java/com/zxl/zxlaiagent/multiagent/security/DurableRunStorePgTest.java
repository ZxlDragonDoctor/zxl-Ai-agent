package com.zxl.zxlaiagent.multiagent.security;

import com.zxl.zxlaiagent.security.SecurityDatabase;
import com.zxl.zxlaiagent.security.SecurityFailure;
import com.zxl.zxlaiagent.security.AuthenticationService;
import com.zxl.zxlaiagent.security.AuthorizationService;
import com.zxl.zxlaiagent.security.ResourceRef;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class DurableRunStorePgTest {
    @Test
    void expiryFencesFormerPublisherAndLeavesLegacyAndCompletedRunsAlone() {
        var store = new DurableRunStore();
        var tenant = "lease-" + UUID.randomUUID();
        var run = UUID.randomUUID().toString();
        var legacy = UUID.randomUUID().toString();
        var completed = UUID.randomUUID().toString();
        try (var db = SecurityDatabase.fromEnvironment()) {
            db.tx(c -> {
                try (var insert = SecurityDatabase.statement(c, "INSERT INTO lesson17.tenant(tenant_id) VALUES (?)", tenant)) {
                    insert.executeUpdate();
                }
                store.appendLeased(c, tenant, run, 1, "queued", "{}", 60_000);
                store.append(c, tenant, legacy, 1, "running", "{}");
                store.appendLeased(c, tenant, completed, 1, "queued", "{}", 60_000);
                store.appendLeased(c, tenant, completed, 2, "completed", "{}", 60_000);
                assertTrue(store.listExpired(c, 50).stream().noneMatch(row -> row.tenantId().equals(tenant)));
                return null;
            });
            try {
                db.tx(c -> {
                    try (var expire = SecurityDatabase.statement(c, """
                            UPDATE lesson17.durable_agent_run
                            SET publication_expires_at=clock_timestamp()-interval '1 second'
                            WHERE tenant_id=? AND publication_expires_at IS NOT NULL
                            """, tenant)) {
                        expire.executeUpdate();
                    }
                    return null;
                });
                assertThrows(SecurityFailure.class, () -> db.tx(c -> {
                    store.appendLeased(c, tenant, run, 2, "completed", "{}", 60_000);
                    return null;
                }));
                db.tx(c -> {
                    var expired = store.listExpired(c, 50).stream().filter(row -> row.tenantId().equals(tenant)).toList();
                    assertEquals(java.util.List.of(run), expired.stream().map(DurableRunStore.TenantRunSnapshot::runId).toList());
                    assertTrue(store.interruptExpired(c, tenant, run, 1, "{}"));
                    assertFalse(store.interruptExpired(c, tenant, run, 1, "{}"));
                    assertEquals("interrupted", store.get(c, tenant, run).orElseThrow().status());
                    return null;
                });
            } finally {
                db.tx(c -> {
                    try (var delete = SecurityDatabase.statement(c, "DELETE FROM lesson17.tenant WHERE tenant_id=?", tenant)) {
                        delete.executeUpdate();
                    }
                    return null;
                });
            }
        }
    }

    @Test
    void cursorReplayIsolationAndRollbackShareTheSnapshotTransaction() {
        var store = new DurableRunStore();
        var run = UUID.randomUUID().toString();
        var tenant = "durable-" + UUID.randomUUID();
        try (var db = SecurityDatabase.fromEnvironment()) {
            db.tx(c -> {
                try (var insert = SecurityDatabase.statement(c, "INSERT INTO lesson17.tenant(tenant_id) VALUES (?)", tenant)) {
                    insert.executeUpdate();
                }
                return null;
            });
            var auth = new AuthenticationService(db);
            var subject = tenant + "-employee";
            auth.provision(subject, subject, "synthetic-password", tenant, "employee");
            var actor = auth.login(subject, "synthetic-password", tenant).actor().ref();
            new AuthorizationService(db).registerResource(actor, new ResourceRef("run", run));
            try {
                db.tx(c -> {
                    store.append(c, tenant, run, 1, "queued", "{}");
                    store.append(c, tenant, run, 2, "running", "{}");
                    store.append(c, tenant, run, 2, "running", "{}");
                    return null;
                });
                assertThrows(SecurityFailure.class, () -> db.tx(c -> {
                    store.append(c, tenant, run, 4, "completed", "{}");
                    return null;
                }));
                assertThrows(SecurityFailure.class, () -> db.tx(c -> {
                    store.append(c, tenant, run, 2, "failed", "{}");
                    return null;
                }));
                assertThrows(IllegalStateException.class, () -> db.tx(c -> {
                    store.append(c, tenant, run, 3, "completed", "{}");
                    throw new IllegalStateException("rollback");
                }));
                try (var reopened = SecurityDatabase.fromEnvironment()) {
                    reopened.tx(c -> {
                        assertEquals(new DurableRunStore.Snapshot(2, "running", "{}"),
                                store.get(c, tenant, run).orElseThrow());
                        var events = store.eventsAfter(c, tenant, run, 1, 50);
                        assertEquals(1, events.size());
                        assertEquals(2, events.get(0).sequence());
                        assertTrue(store.get(c, "different-tenant", run).isEmpty());
                        assertTrue(store.eventsAfter(c, "different-tenant", run, 0, 50).isEmpty());
                        assertEquals(run, store.listOwned(c, tenant, subject, 50).get(0).runId());
                        assertTrue(store.listOwned(c, tenant, "other-subject", 50).isEmpty());
                        assertFalse(store.interruptIfCurrent(c, tenant, run, 1, "{}"));
                        assertTrue(store.interruptIfCurrent(c, tenant, run, 2, "{}"));
                        assertFalse(store.interruptIfCurrent(c, tenant, run, 3, "{}"));
                        assertEquals("interrupted", store.get(c, tenant, run).orElseThrow().status());
                        return null;
                    });
                }
            } finally {
                db.tx(c -> {
                    try (var delete = SecurityDatabase.statement(c, "DELETE FROM lesson17.tenant WHERE tenant_id=?", tenant)) {
                        delete.executeUpdate();
                    }
                    try (var delete = SecurityDatabase.statement(c, "DELETE FROM lesson17.subject WHERE subject_id=?", subject)) {
                        delete.executeUpdate();
                    }
                    return null;
                });
            }
        }
    }
}
