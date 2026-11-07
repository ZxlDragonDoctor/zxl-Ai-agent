package com.zxl.zxlaiagent.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zxl.zxlaiagent.conversation.repository.LegacyConversationFiles;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Offline administration. PostgreSQL commits the ownership index and its evidence together. */
public final class LegacyHistoryImporter {
    private final SecurityDatabase db;
    private final Path source;
    private final Path backup;
    private final LegacyConversationFiles history;

    public LegacyHistoryImporter(SecurityDatabase db, Path source, Path backup, ObjectMapper mapper) {
        this.db = db;
        this.source = source.toAbsolutePath().normalize();
        this.backup = backup.toAbsolutePath().normalize();
        if (this.source.startsWith(this.backup) || this.backup.startsWith(this.source))
            throw new IllegalArgumentException("Source and backup directories must be separate");
        history = new LegacyConversationFiles(this.source, mapper);
    }

    public enum Result { IMPORTED, ALREADY_IMPORTED }

    public Result importHistory(LegacyHistoryManifest.Loaded loaded) {
        var manifest = loaded.manifest();
        var entries = manifest.entries().stream().sorted(Comparator.comparing(LegacyHistoryManifest.Entry::conversationId)).toList();
        return db.tx(c -> {
            // Serialize offline imports before backup creation. No online operation takes this lock.
            try (var lock = SecurityDatabase.statement(c, "SELECT pg_advisory_xact_lock(17000003::bigint)")) {
                lock.execute();
            }
            boolean replay = receiptMatches(c, loaded);
            var sizes = backupEntries(manifest, entries);
            lockActiveTarget(c, manifest);
            for (var entry : entries) {
                if (replay) verifyExistingEntry(c, manifest, entry);
                else reserveOwner(c, manifest, entry);
            }
            if (replay) return Result.ALREADY_IMPORTED;
            writeReceipt(c, loaded, entries, sizes);
            return Result.IMPORTED;
        });
    }

    private List<Integer> backupEntries(LegacyHistoryManifest manifest, List<LegacyHistoryManifest.Entry> entries) {
        try {
            LegacyConversationFiles.requireDirectory(source);
            LegacyConversationFiles.requireDirectory(backup);
            Path destination = backup.resolve(manifest.importId());
            try { Files.createDirectory(destination); }
            catch (FileAlreadyExistsException existing) { /* verify the existing directory below */ }
            LegacyConversationFiles.requireDirectory(destination);
            var sizes = new ArrayList<Integer>();
            for (var entry : entries) {
                UUID id = UUID.fromString(entry.conversationId());
                var snapshot = history.read(id, entry.sha256());
                Path path = destination.resolve(id + ".json");
                try (var file = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS)) {
                    var buffer = ByteBuffer.wrap(snapshot.bytes());
                    while (buffer.hasRemaining()) file.write(buffer);
                    file.force(true);
                } catch (FileAlreadyExistsException existing) { /* verify; never replace an existing backup */ }
                byte[] saved = LegacyConversationFiles.readBounded(path, LegacyConversationFiles.MAX_BYTES);
                if (!entry.sha256().equals(LegacyConversationFiles.sha256(saved)))
                    throw new IllegalStateException("Archive backup digest mismatch");
                sizes.add(saved.length);
            }
            for (var entry : entries) history.read(UUID.fromString(entry.conversationId()), entry.sha256());
            return List.copyOf(sizes);
        } catch (IOException | SecurityException failure) {
            throw new IllegalStateException("Archive backup cannot be verified");
        }
    }

    private static boolean receiptMatches(Connection c, LegacyHistoryManifest.Loaded loaded) throws SQLException {
        var manifest = loaded.manifest();
        try (var s = SecurityDatabase.statement(c, """
                SELECT tenant_id,subject_id,manifest_sha256,entry_count,
                    (SELECT count(*) FROM legacy_import_entry e WHERE e.import_id=r.import_id)
                FROM legacy_import_receipt r WHERE import_id=?
                """, manifest.importId()); var rows = s.executeQuery()) {
            if (!rows.next()) return false;
            if (!manifest.tenantId().equals(rows.getString(1)) || !manifest.subjectId().equals(rows.getString(2))
                    || !loaded.sha256().equals(rows.getString(3)) || manifest.entries().size() != rows.getInt(4)
                    || manifest.entries().size() != rows.getInt(5)) throw conflict();
            return true;
        }
    }

    private static void lockActiveTarget(Connection c, LegacyHistoryManifest manifest) throws SQLException {
        AuthenticationService.lockEpoch(c, new ActorRef(manifest.subjectId(), manifest.tenantId(), "offline-archive-import", 1));
        try (var s = SecurityDatabase.statement(c, """
                SELECT 1 FROM membership m JOIN subject u USING(subject_id) JOIN tenant t USING(tenant_id)
                WHERE m.tenant_id=? AND m.subject_id=? AND m.role='employee'
                    AND m.status='ACTIVE' AND u.status='ACTIVE' AND t.status='ACTIVE' FOR UPDATE OF m
                """, manifest.tenantId(), manifest.subjectId()); var rows = s.executeQuery()) {
            if (!rows.next()) throw new SecurityFailure("ARCHIVE_TARGET_UNAVAILABLE", 409);
        }
    }

    private static void reserveOwner(Connection c, LegacyHistoryManifest manifest, LegacyHistoryManifest.Entry entry) throws SQLException {
        try (var s = SecurityDatabase.statement(c, """
                INSERT INTO resource_owner(resource_type,resource_id,tenant_id,subject_id,legacy_import_id)
                VALUES ('conversation',?,?,?,?) ON CONFLICT DO NOTHING
                """, entry.conversationId(), manifest.tenantId(), manifest.subjectId(), manifest.importId())) {
            if (s.executeUpdate() != 1) throw conflict();
        }
    }

    private static void verifyExistingEntry(Connection c, LegacyHistoryManifest manifest, LegacyHistoryManifest.Entry entry) throws SQLException {
        try (var s = SecurityDatabase.statement(c, """
                SELECT e.sha256 FROM resource_owner o JOIN legacy_import_entry e
                    ON e.import_id=o.legacy_import_id AND e.conversation_id=o.resource_id
                WHERE o.resource_type='conversation' AND o.resource_id=? AND o.tenant_id=? AND o.subject_id=?
                    AND o.legacy_import_id=? AND o.state='ACTIVE' FOR UPDATE OF o
                """, entry.conversationId(), manifest.tenantId(), manifest.subjectId(), manifest.importId()); var rows = s.executeQuery()) {
            if (!rows.next() || !entry.sha256().equals(rows.getString(1))) throw conflict();
        }
    }

    private static void writeReceipt(Connection c, LegacyHistoryManifest.Loaded loaded,
            List<LegacyHistoryManifest.Entry> entries, List<Integer> sizes) throws SQLException {
        var manifest = loaded.manifest();
        try (var s = SecurityDatabase.statement(c, """
                INSERT INTO legacy_import_receipt(import_id,tenant_id,subject_id,manifest_sha256,entry_count) VALUES (?,?,?,?,?)
                """, manifest.importId(), manifest.tenantId(), manifest.subjectId(), loaded.sha256(), entries.size())) {
            s.executeUpdate();
        }
        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.get(i);
            try (var s = SecurityDatabase.statement(c, """
                    INSERT INTO legacy_import_entry(import_id,conversation_id,sha256,size_bytes) VALUES (?,?,?,?)
                    """, manifest.importId(), entry.conversationId(), entry.sha256(), sizes.get(i))) {
                s.executeUpdate();
            }
        }
    }

    private static SecurityFailure conflict() { return new SecurityFailure("ARCHIVE_IMPORT_CONFLICT", 409); }
}
