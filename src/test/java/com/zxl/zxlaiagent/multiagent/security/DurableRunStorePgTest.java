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
