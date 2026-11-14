package com.zxl.zxlaiagent.multiagent.security;

import com.zxl.zxlaiagent.security.SecurityFailure;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.zxl.zxlaiagent.security.SecurityDatabase.statement;

/** Stores the public projections inside the caller's authorized transaction. */
public final class DurableRunStore {
    private static final Set<String> ACTIVE = Set.of("queued", "planning", "running", "synthesizing");

    public record Snapshot(long sequence, String status, String json) {}
    public record RunSnapshot(String runId, Snapshot snapshot) {}
    public record TenantRunSnapshot(String tenantId, String runId, Snapshot snapshot) {}

    public void append(Connection c, String tenantId, String runId, long sequence, String status, String json)
            throws SQLException {
        requireTransaction(c);
        var id = UUID.fromString(runId);
        if (sequence < 1) throw gap();

        if (sequence == 1 && insertRun(c, tenantId, id)) {
            insertEvent(c, tenantId, id, sequence, status, json);
            return;
        }

        long current;
        try (var query = statement(c, """
                SELECT latest_sequence FROM lesson17.durable_agent_run
                WHERE tenant_id=? AND run_id=? FOR UPDATE
                """, tenantId, id); var rows = query.executeQuery()) {
            if (!rows.next()) throw gap();
            current = rows.getLong(1);
        }
        if (sequence <= current) {
            requireIdentical(c, tenantId, id, sequence, status, json);
            return;
        }
        if (sequence - current != 1) throw gap();
        insertEvent(c, tenantId, id, sequence, status, json);
        try (var update = statement(c, """
                UPDATE lesson17.durable_agent_run SET latest_sequence=?, updated_at=clock_timestamp()
                WHERE tenant_id=? AND run_id=?
                """, sequence, tenantId, id)) {
            update.executeUpdate();
        }
    }

    /** The database clock owns the publication cutoff. No writer may renew an expired budget. */
    public void appendLeased(Connection c, String tenantId, String runId, long sequence, String status,
                             String json, long publicationBudgetMs) throws SQLException {
        requireTransaction(c);
        UUID id = UUID.fromString(runId);
        if (sequence == 1) {
            try (var insert = statement(c, """
                    INSERT INTO lesson17.durable_agent_run (tenant_id,run_id,latest_sequence,publication_expires_at)
                    VALUES (?,?,1,clock_timestamp()+(? * interval '1 millisecond')) ON CONFLICT DO NOTHING
                    """, tenantId, id, publicationBudgetMs)) {
                if (insert.executeUpdate() != 1) throw publicationExpired();
            }
            insertEvent(c, tenantId, id, sequence, status, json);
            return;
        }
        try (var lock = statement(c, """
                SELECT 1 FROM lesson17.durable_agent_run
                WHERE tenant_id=? AND run_id=? FOR UPDATE
                """, tenantId, id); var rows = lock.executeQuery()) {
            if (!rows.next()) throw publicationExpired();
        }
        try (var check = statement(c, """
                SELECT publication_expires_at>clock_timestamp() FROM lesson17.durable_agent_run
                WHERE tenant_id=? AND run_id=?
                """, tenantId, id); var rows = check.executeQuery()) {
            if (!rows.next() || !rows.getBoolean(1)) throw publicationExpired();
        }
        append(c, tenantId, runId, sequence, status, json);
        if (!ACTIVE.contains(status)) {
            try (var clear = statement(c, """
                    UPDATE lesson17.durable_agent_run SET publication_expires_at=NULL
                    WHERE tenant_id=? AND run_id=?
                    """, tenantId, id)) {
                clear.executeUpdate();
            }
        }
    }

    private static SecurityFailure publicationExpired() {
        return new SecurityFailure("RUN_PUBLICATION_EXPIRED", 409);
    }

    public List<RunSnapshot> listOwned(Connection c, String tenantId, String subjectId, int limit)
            throws SQLException {
        requireTransaction(c);
        requireLimit(limit);
        var result = new ArrayList<RunSnapshot>();
        try (var query = statement(c, """
                SELECT e.event_sequence,e.status,e.public_snapshot,r.run_id
                FROM lesson17.durable_agent_run r
                JOIN lesson17.resource_owner o ON o.tenant_id=r.tenant_id AND o.resource_id=r.run_id::text
                    AND o.resource_type='run' AND o.state='ACTIVE'
                JOIN lesson17.durable_agent_run_event e ON e.tenant_id=r.tenant_id AND e.run_id=r.run_id
                    AND e.event_sequence=r.latest_sequence
                WHERE r.tenant_id=? AND o.subject_id=?
                ORDER BY r.updated_at DESC,r.run_id LIMIT ?
                """, tenantId, subjectId, limit); var rows = query.executeQuery()) {
            while (rows.next()) result.add(new RunSnapshot(rows.getString(4), read(rows)));
        }
        return List.copyOf(result);
    }

    public List<TenantRunSnapshot> listExpired(Connection c, int limit) throws SQLException {
        requireTransaction(c);
        requireLimit(limit);
        var result = new ArrayList<TenantRunSnapshot>();
        try (var query = statement(c, """
                SELECT e.event_sequence,e.status,e.public_snapshot,r.tenant_id,r.run_id
                FROM lesson17.durable_agent_run r
                JOIN lesson17.durable_agent_run_event e ON e.tenant_id=r.tenant_id AND e.run_id=r.run_id
                    AND e.event_sequence=r.latest_sequence
                WHERE r.publication_expires_at<=clock_timestamp()
                    AND e.status IN ('queued','planning','running','synthesizing')
                ORDER BY r.publication_expires_at,r.tenant_id,r.run_id LIMIT ?
                """, limit); var rows = query.executeQuery()) {
            while (rows.next()) result.add(new TenantRunSnapshot(rows.getString(4), rows.getString(5), read(rows)));
        }
        return List.copyOf(result);
    }

    public boolean interruptExpired(Connection c, String tenantId, String runId, long sequence, String json)
            throws SQLException {
        requireTransaction(c);
        try (var lock = statement(c, """
                SELECT 1 FROM lesson17.durable_agent_run WHERE tenant_id=? AND run_id=?
                    AND publication_expires_at<=clock_timestamp() FOR UPDATE
                """, tenantId, UUID.fromString(runId)); var rows = lock.executeQuery()) {
            if (!rows.next() || !interruptIfCurrent(c, tenantId, runId, sequence, json)) return false;
        }
        try (var clear = statement(c, """
                UPDATE lesson17.durable_agent_run SET publication_expires_at=NULL
                WHERE tenant_id=? AND run_id=?
                """, tenantId, UUID.fromString(runId))) {
            clear.executeUpdate();
        }
        return true;
    }

    /** Caller must prove the owning instance is stopped before invoking this compare-and-set. */
    public boolean interruptIfCurrent(Connection c, String tenantId, String runId, long expectedSequence,
                                      String interruptedJson) throws SQLException {
        requireTransaction(c);
        try (var query = statement(c, """
                SELECT e.status FROM lesson17.durable_agent_run r
                JOIN lesson17.durable_agent_run_event e ON e.tenant_id=r.tenant_id AND e.run_id=r.run_id
                    AND e.event_sequence=r.latest_sequence
                WHERE r.tenant_id=? AND r.run_id=? AND r.latest_sequence=? FOR UPDATE OF r
                """, tenantId, UUID.fromString(runId), expectedSequence); var rows = query.executeQuery()) {
            if (!rows.next() || !ACTIVE.contains(rows.getString(1))) return false;
        }
        append(c, tenantId, runId, Math.incrementExact(expectedSequence), "interrupted", interruptedJson);
        return true;
    }

    public Optional<Snapshot> get(Connection c, String tenantId, String runId) throws SQLException {
        requireTransaction(c);
        try (var query = statement(c, """
                SELECT e.event_sequence,e.status,e.public_snapshot FROM lesson17.durable_agent_run r
                JOIN lesson17.durable_agent_run_event e ON e.tenant_id=r.tenant_id AND e.run_id=r.run_id
                    AND e.event_sequence=r.latest_sequence
                WHERE r.tenant_id=? AND r.run_id=?
                """, tenantId, UUID.fromString(runId)); var rows = query.executeQuery()) {
            return rows.next() ? Optional.of(read(rows)) : Optional.empty();
        }
    }

    public List<Snapshot> eventsAfter(Connection c, String tenantId, String runId, long cursor, int limit)
            throws SQLException {
        requireTransaction(c);
        requireLimit(limit);
        if (cursor < 0) throw new IllegalArgumentException("Invalid event cursor");
        var events = new ArrayList<Snapshot>();
        try (var query = statement(c, """
                SELECT event_sequence,status,public_snapshot FROM lesson17.durable_agent_run_event
                WHERE tenant_id=? AND run_id=? AND event_sequence>? ORDER BY event_sequence LIMIT ?
                """, tenantId, UUID.fromString(runId), cursor, limit); var rows = query.executeQuery()) {
            while (rows.next()) events.add(read(rows));
        }
        return List.copyOf(events);
    }

    private static boolean insertRun(Connection c, String tenantId, UUID runId) throws SQLException {
        try (var insert = statement(c, """
                INSERT INTO lesson17.durable_agent_run (tenant_id,run_id,latest_sequence)
                VALUES (?,?,1) ON CONFLICT DO NOTHING
                """, tenantId, runId)) {
            return insert.executeUpdate() == 1;
        }
    }

    private static void insertEvent(Connection c, String tenantId, UUID runId, long sequence, String status, String json)
            throws SQLException {
        try (var insert = statement(c, """
                INSERT INTO lesson17.durable_agent_run_event (tenant_id,run_id,event_sequence,status,public_snapshot)
                VALUES (?,?,?,?,?)
                """, tenantId, runId, sequence, status, json)) {
            insert.executeUpdate();
        }
    }

    private static void requireIdentical(Connection c, String tenantId, UUID runId, long sequence, String status, String json)
            throws SQLException {
        try (var query = statement(c, """
                SELECT status,public_snapshot FROM lesson17.durable_agent_run_event
                WHERE tenant_id=? AND run_id=? AND event_sequence=?
                """, tenantId, runId, sequence); var rows = query.executeQuery()) {
            if (!rows.next() || !rows.getString(1).equals(status) || !rows.getString(2).equals(json)) {
                throw new SecurityFailure("RUN_EVENT_CONFLICT", 409);
            }
        }
    }

    private static Snapshot read(ResultSet rows) throws SQLException {
        return new Snapshot(rows.getLong(1), rows.getString(2), rows.getString(3));
    }

    private static SecurityFailure gap() {
        return new SecurityFailure("RUN_EVENT_SEQUENCE_GAP", 409);
    }

    private static void requireTransaction(Connection c) throws SQLException {
        if (c.getAutoCommit()) throw new IllegalArgumentException("Run history requires a caller-owned transaction");
    }

    private static void requireLimit(int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid history page size");
    }
}
