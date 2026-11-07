package com.zxl.zxlaiagent.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zxl.zxlaiagent.conversation.model.Conversation;
import com.zxl.zxlaiagent.conversation.repository.LegacyConversationFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class LegacyHistoryImporterPgTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test void concurrentReplayCommitsOnceAndOnlyTargetCanReadWithoutWriting() throws Exception {
        try (var db = SecurityDatabase.fromEnvironment(); var fixture = new Fixture(db)) {
            var entry = fixture.entry();
            var manifest = fixture.manifest(UUID.randomUUID(), List.of(entry));
            assertEquals("RESOURCE_UNAVAILABLE", assertThrows(SecurityFailure.class,
                    () -> fixture.authorization.legacyConversationDigest(fixture.owner, UUID.fromString(entry.conversationId()))).code());
            var executor = Executors.newFixedThreadPool(2);
            try {
                var gate = new CountDownLatch(1);
                Callable<LegacyHistoryImporter.Result> importHistory = () -> {
                    gate.await();
                    return fixture.importer.importHistory(manifest);
                };
                var first = executor.submit(importHistory);
                var second = executor.submit(importHistory);
                gate.countDown();
                assertEquals(Set.of(LegacyHistoryImporter.Result.IMPORTED, LegacyHistoryImporter.Result.ALREADY_IMPORTED),
                        Set.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)));
            } finally { executor.shutdownNow(); }
            var id = UUID.fromString(entry.conversationId());
            var resource = new ResourceRef("conversation", entry.conversationId());
            assertEquals(entry.sha256(), fixture.authorization.legacyConversationDigest(fixture.owner, id));
            assertEquals("RESOURCE_UNAVAILABLE", assertThrows(SecurityFailure.class,
                    () -> fixture.authorization.legacyConversationDigest(fixture.foreign, id)).code());
            assertEquals("RESOURCE_UNAVAILABLE", assertThrows(SecurityFailure.class,
                    () -> fixture.authorization.legacyConversationDigest(fixture.colleague, id)).code());
            assertEquals("ARCHIVE_READ_ONLY", fixture.authorization.decide(fixture.owner, "conversation.writeOwn", resource, Phase.ENTRY, null).code());
            assertArrayEquals(Files.readAllBytes(fixture.source.resolve(id + ".json")),
                    Files.readAllBytes(fixture.backup.resolve(manifest.manifest().importId()).resolve(id + ".json")));
            try (var reopened = SecurityDatabase.fromEnvironment()) {
                assertEquals(entry.sha256(), new AuthorizationService(reopened).legacyConversationDigest(fixture.owner, id));
            }
            fixture.authorization.setMembershipActive(fixture.owner, false);
            assertThrows(SecurityFailure.class, () -> fixture.importer.importHistory(manifest));
        }
    }

    @Test void invalidFileOrOwnerConflictNeverLeavesPartialOwnershipOrReceipt() throws Exception {
        try (var db = SecurityDatabase.fromEnvironment(); var fixture = new Fixture(db)) {
            var valid = fixture.entry();
            var bad = fixture.entry();
            var changed = new LegacyHistoryManifest.Entry(bad.conversationId(), "0".repeat(64));
            UUID invalidId = UUID.randomUUID();
            var invalid = fixture.manifest(invalidId, List.of(valid, changed));
            assertThrows(IllegalStateException.class, () -> fixture.importer.importHistory(invalid));
            assertTrue(fixture.authorization.ownedResourceIds(fixture.owner, "conversation").isEmpty());
            fixture.authorization.registerResource(fixture.foreign, new ResourceRef("conversation", bad.conversationId()));
            var conflict = fixture.manifest(UUID.randomUUID(), List.of(valid, bad));
            assertThrows(SecurityFailure.class, () -> fixture.importer.importHistory(conflict));
            assertTrue(fixture.authorization.ownedResourceIds(fixture.owner, "conversation").isEmpty());
            assertEquals(List.of(bad.conversationId()), fixture.authorization.ownedResourceIds(fixture.foreign, "conversation"));
            assertEquals(0, db.tx(c -> {
                try (var s = SecurityDatabase.statement(c, "SELECT count(*) FROM legacy_import_receipt WHERE subject_id=?", fixture.subject);
                     var rows = s.executeQuery()) { rows.next(); return rows.getInt(1); }
            }).intValue());
        }
    }

    private final class Fixture implements AutoCloseable {
        final SecurityDatabase db;
        final String subject = "legacy-test-" + UUID.randomUUID();
        final Path source = Files.createDirectory(directory.resolve("source"));
        final Path backup = Files.createDirectory(directory.resolve("backup"));
        final AuthorizationService authorization;
        final LegacyHistoryImporter importer;
        final ActorRef owner;
        final ActorRef foreign;
        final ActorRef colleague;

        Fixture(SecurityDatabase db) throws Exception {
            this.db = db;
            var auth = new AuthenticationService(db);
            authorization = new AuthorizationService(db);
            importer = new LegacyHistoryImporter(db, source, backup, mapper);
            auth.provision(subject, subject, "synthetic-password", subject, "employee");
            auth.provision(subject + "-foreign", subject + "-foreign", "synthetic-password", subject + "-foreign", "employee");
            auth.provision(subject + "-colleague", subject + "-colleague", "synthetic-password", subject, "employee");
            owner = auth.login(subject, "synthetic-password", subject).actor().ref();
            foreign = auth.login(subject + "-foreign", "synthetic-password", subject + "-foreign").actor().ref();
            colleague = auth.login(subject + "-colleague", "synthetic-password", subject).actor().ref();
        }

        LegacyHistoryManifest.Entry entry() throws Exception {
            UUID id = UUID.randomUUID();
            var body = new Conversation(id, "synthetic", Instant.now(), Instant.now(), 0, List.of(), List.of(), "manus");
            byte[] bytes = mapper.writeValueAsBytes(body);
            Files.write(source.resolve(id + ".json"), bytes);
            return new LegacyHistoryManifest.Entry(id.toString(), LegacyConversationFiles.sha256(bytes));
        }

        LegacyHistoryManifest.Loaded manifest(UUID id, List<LegacyHistoryManifest.Entry> entries) throws Exception {
            Path path = directory.resolve(id + ".json");
            mapper.writeValue(path.toFile(), new LegacyHistoryManifest(id.toString(), subject, subject, entries));
            return LegacyHistoryManifest.load(path, mapper);
        }

        @Override public void close() {
            db.tx(c -> {
                for (String sql : List.of("DELETE FROM authorization_audit WHERE subject_id=?", "DELETE FROM subject WHERE subject_id=?")) {
                    try (var s = SecurityDatabase.statement(c, sql, subject + "-colleague")) { s.executeUpdate(); }
                }
                for (String sql : List.of("DELETE FROM legacy_import_receipt WHERE subject_id IN (?,?)",
                        "DELETE FROM authorization_audit WHERE subject_id IN (?,?)", "DELETE FROM subject WHERE subject_id IN (?,?)",
                        "DELETE FROM tenant WHERE tenant_id IN (?,?)")) {
                    try (var s = SecurityDatabase.statement(c, sql, subject, subject + "-foreign")) { s.executeUpdate(); }
                }
                return null;
            });
        }
    }
}
