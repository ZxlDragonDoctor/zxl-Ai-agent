package com.zxl.zxlaiagent.multiagent.security;

import com.zxl.zxlaiagent.security.SecurityFailure;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.zxl.zxlaiagent.security.SecurityDatabase.statement;

/** Stores the public projections inside the caller's authorized transaction. */
public final class DurableRunStore {
    public record Snapshot(long sequence, String status, String json) {}

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
                UPDATE lesson17.durable_agent_run SET latest_sequence=? WHERE tenant_id=? AND run_id=?
                """, sequence, tenantId, id)) {
            update.executeUpdate();
        }
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
        if (cursor < 0 || limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid event page");
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
}
