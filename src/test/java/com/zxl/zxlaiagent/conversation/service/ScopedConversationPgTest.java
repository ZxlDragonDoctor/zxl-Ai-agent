package com.zxl.zxlaiagent.conversation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zxl.zxlaiagent.agent.ToolCallAgent;
import com.zxl.zxlaiagent.conversation.agent.*;
import com.zxl.zxlaiagent.conversation.repository.FileConversationRepository;
import com.zxl.zxlaiagent.security.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real PG owner index plus real temporary JSON bodies; no model or HTTP is claimed here. */
class ScopedConversationPgTest {
    @TempDir Path directory;
    SecurityDatabase database;
    AuthenticationService authentication;
    AuthorizationService authorization;
    FileConversationRepository repository;
    ConversationService service;
    String first, second, peer, firstTenant, secondTenant;
    ActorRef actorA, actorB, actorPeer;

    @BeforeEach void setup() {
        database = SecurityDatabase.fromEnvironment();
        authentication = new AuthenticationService(database);
        authorization = new AuthorizationService(database);
        String suffix = UUID.randomUUID().toString();
        first = "conv-a-" + suffix; second = "conv-b-" + suffix; peer = "conv-peer-" + suffix;
        firstTenant = "conv-ta-" + suffix; secondTenant = "conv-tb-" + suffix;
        authentication.provision(first, first, "synthetic-password", firstTenant, "employee");
        authentication.provision(second, second, "synthetic-password", secondTenant, "employee");
        authentication.provision(peer, peer, "synthetic-password", firstTenant, "employee");
        actorA = authentication.login(first, "synthetic-password", firstTenant).actor().ref();
        actorB = authentication.login(second, "synthetic-password", secondTenant).actor().ref();
        actorPeer = authentication.login(peer, "synthetic-password", firstTenant).actor().ref();
        repository = spy(new FileConversationRepository(new ObjectMapper().findAndRegisterModules(), directory.toString()));
        ConversationAgentProvider provider = new ConversationAgentProvider() {
            public AgentDefinition definition() { return new AgentDefinition("safe", "安全", "Safe", "", ""); }
            public ToolCallAgent create() { throw new AssertionError("Creation does not start a model"); }
        };
        service = new ConversationService(repository, new ConversationRunGuard(), new ConversationAgentRegistry(List.of(provider)));
        service.setAuthorizationService(authorization);
    }

    @AfterEach void cleanup() {
        if (database == null || first == null) return;
        database.tx(connection -> {
            for (String subject : List.of(first, second, peer)) {
                try (var statement = SecurityDatabase.statement(connection, "DELETE FROM authorization_audit WHERE subject_id=?", subject)) { statement.executeUpdate(); }
                try (var statement = SecurityDatabase.statement(connection, "DELETE FROM subject WHERE subject_id=?", subject)) { statement.executeUpdate(); }
            }
            for (String tenant : List.of(firstTenant, secondTenant)) {
                try (var statement = SecurityDatabase.statement(connection, "DELETE FROM tenant WHERE tenant_id=?", tenant)) { statement.executeUpdate(); }
            }
            return null;
        });
    }

    @Test void tenantAndSubjectBothConstrainReadsBeforeBodyAccess() {
        var a = service.create(actorA, "A 的正文", "safe");
        var b = service.create(actorB, "B 的正文", "safe");
        clearInvocations(repository);
        for (ActorRef stranger : List.of(actorB, actorPeer)) {
            assertEquals("RESOURCE_UNAVAILABLE", assertThrows(SecurityFailure.class, () -> service.require(stranger, a.id())).code());
        }
        verify(repository, never()).find(a.id());
        assertEquals(List.of(b.id()), service.list(actorB).stream().map(summary -> summary.id()).toList());
        verify(repository, never()).list();
        verify(repository, never()).find(a.id());
    }

    @Test void existingLegacyFileNeverBecomesOwnedByFirstLogin() {
        var legacy = repository.create("保留的历史", "safe");
        clearInvocations(repository);
        assertThrows(SecurityFailure.class, () -> service.require(actorA, legacy.id()));
        assertTrue(service.list(actorA).isEmpty());
        verify(repository, never()).find(legacy.id());
        verify(repository, never()).list();
    }

    @Test void revokeStopsFurtherReadsAndWritesWhileAnotherTenantStillWorks() {
        var a = service.create(actorA, "A", "safe");
        var b = service.create(actorB, "B", "safe");
        authentication.revoke(actorA);
        clearInvocations(repository);
        assertEquals(401, assertThrows(SecurityFailure.class, () -> service.require(actorA, a.id())).status());
        assertEquals(401, assertThrows(SecurityFailure.class, () -> service.delete(actorA, a.id())).status());
        verify(repository, never()).find(a.id());
        verify(repository, never()).delete(a.id());
        assertEquals(b.id(), service.require(actorB, b.id()).id());
    }

    @Test void reservedOwnerWithoutFileIsNotInventedAsAConversation() {
        authorization.registerResource(actorA, new ResourceRef("conversation", UUID.randomUUID().toString()));
        assertTrue(service.list(actorA).isEmpty());
    }
}
