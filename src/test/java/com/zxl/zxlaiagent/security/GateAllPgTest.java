package com.zxl.zxlaiagent.security;

import com.zxl.zxlaiagent.security.execution.ActorBoundary;
import org.junit.jupiter.api.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class GateAllPgTest {
    SecurityDatabase db;
    AuthenticationService authentication;
    AuthorizationService authorization;
    ActorRef actor;
    String subject, tenant;

    @BeforeEach void setup() {
        db = SecurityDatabase.fromEnvironment(); authentication = new AuthenticationService(db);
        authorization = new AuthorizationService(db); subject = "gate-all-" + UUID.randomUUID(); tenant = subject + "-tenant";
        authentication.provision(subject, subject, "synthetic-password", tenant, "employee");
        actor = authentication.login(subject, "synthetic-password", tenant).actor().ref();
    }

    @AfterEach void cleanup() {
        if (db == null || subject == null) return;
        db.tx(connection -> {
            try (var statement = SecurityDatabase.statement(connection, "DELETE FROM authorization_audit WHERE subject_id=?", subject)) { statement.executeUpdate(); }
            try (var statement = SecurityDatabase.statement(connection, "DELETE FROM subject WHERE subject_id=?", subject)) { statement.executeUpdate(); }
            try (var statement = SecurityDatabase.statement(connection, "DELETE FROM tenant WHERE tenant_id=?", tenant)) { statement.executeUpdate(); }
            return null;
        });
    }

    List<AuthorizationService.Permission> permissions(ResourceRef resource) {
        return List.of(new AuthorizationService.Permission("agent.execute", null),
                new AuthorizationService.Permission("conversation.readOwn", resource));
    }

    int audits(String run) {
        return db.tx(connection -> {
            try (var statement = SecurityDatabase.statement(connection, "SELECT count(*) FROM authorization_audit WHERE run_id=? AND subject_id=?", run, subject);
                 var rows = statement.executeQuery()) { rows.next(); return rows.getInt(1); }
        });
    }

    @Test void allPermissionsAuthorizeOneStartAndEachDecisionIsAudited() {
        var resource = new ResourceRef("conversation", UUID.randomUUID().toString()); authorization.registerResource(actor, resource);
        var calls = new AtomicInteger();
        String result = authorization.gateAll(actor, permissions(resource), Phase.MODEL_DISPATCH, "composite", () -> { calls.incrementAndGet(); return "started"; });
        assertEquals("started", result); assertEquals(1, calls.get()); assertEquals(2, audits("composite"));
        int versions = db.tx(connection -> {
            try (var statement = SecurityDatabase.statement(connection, "SELECT count(DISTINCT version) FROM authorization_audit WHERE subject_id=? AND run_id='composite'", subject);
                 var rows = statement.executeQuery()) { rows.next(); return rows.getInt(1); }
        });
        assertEquals(1, versions);
    }

    @Test void aDeniedRequirementCommitsAuditAndNeverStarts() {
        var calls = new AtomicInteger();
        var resource = new ResourceRef("conversation", UUID.randomUUID().toString());
        var failure = assertThrows(SecurityFailure.class, () -> authorization.gateAll(actor, permissions(resource), Phase.MODEL_DISPATCH,
                "denied", () -> calls.incrementAndGet()));
        assertEquals("RESOURCE_UNAVAILABLE", failure.code()); assertEquals(0, calls.get()); assertEquals(2, audits("denied"));
    }

    @Test void immediateDispatchFailureDoesNotEraseTheActualAuthorizationAudit() {
        var failure = assertThrows(IllegalStateException.class, () -> authorization.gateAll(actor,
                List.of(new AuthorizationService.Permission("agent.execute", null)), Phase.MODEL_DISPATCH,
                "start-failed", () -> { throw new IllegalStateException("synthetic-start-failed"); }));
        assertEquals("synthetic-start-failed", failure.getMessage()); assertEquals(1, audits("start-failed"));
    }

    @Test void existingSingleGateAlsoKeepsItsDispatchFailureAudit() {
        assertThrows(IllegalStateException.class, () -> authorization.gate(actor, "agent.execute", null, Phase.MODEL_DISPATCH,
                "single-failed", () -> { throw new IllegalStateException("synthetic-start-failed"); }));
        assertEquals(1, audits("single-failed"));
    }

    @Test void waitingForRemoteCompletionDoesNotHoldEpochAndRevocationStopsPublish() {
        var resource = new ResourceRef("conversation", UUID.randomUUID().toString()); authorization.registerResource(actor, resource);
        var boundary = new ActorBoundary(authorization, actor, permissions(resource), "pending");
        var remote = new CompletableFuture<String>();
        assertSame(remote, boundary.start(Phase.MODEL_DISPATCH, () -> remote));
        assertFalse(remote.isDone()); authentication.revoke(actor);
        remote.complete("late-private-result");
        assertEquals(401, assertThrows(SecurityFailure.class, () -> boundary.check(Phase.RESULT_PUBLISH)).status());
    }
}
