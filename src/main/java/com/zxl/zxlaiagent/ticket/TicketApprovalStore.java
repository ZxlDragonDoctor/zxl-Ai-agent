package com.zxl.zxlaiagent.ticket;

import com.zxl.zxlaiagent.security.ActorRef;
import com.zxl.zxlaiagent.security.SecurityFailure;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static com.zxl.zxlaiagent.security.SecurityDatabase.statement;

/** SQL-only storage. Call inside the caller's authorized PostgreSQL transaction. */
public final class TicketApprovalStore {
    public void insert(Connection c, TicketApproval proposal) throws SQLException {
        if (!insertPending(c, proposal)) throw new SecurityFailure("APPROVAL_CONFLICT", 409);
    }

    TicketApproval insertOrReplay(Connection c, TicketApproval proposal) throws SQLException {
        if (insertPending(c, proposal)) return proposal;
        var existing = lock(c, proposal.requester().tenantId(), proposal.proposalId());
        if (!existing.requester().subjectId().equals(proposal.requester().subjectId())
                || !existing.command().equals(proposal.command())) {
            throw new SecurityFailure("APPROVAL_CONFLICT", 409);
        }
        return existing;
    }

    private boolean insertPending(Connection c, TicketApproval proposal) throws SQLException {
        requireTransaction(c);
        if (proposal.outcome() != TicketApproval.Outcome.PENDING) throw new IllegalArgumentException("Pending proposal required");
        var actor = proposal.requester();
        var command = proposal.command();
        try (var s = statement(c, """
                INSERT INTO lesson17.ticket_approval (tenant_id, proposal_id, requester_id, requester_session,
                    requester_auth_version, operation_id, title, description, parameter_sha256, expires_at, outcome, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', 1) ON CONFLICT DO NOTHING
                """, actor.tenantId(), proposal.proposalId(), actor.subjectId(), actor.sessionId(),
                actor.authenticationVersion(), command.operationId(), command.title(), command.description(),
                command.fingerprint(), Timestamp.from(proposal.expiresAt()))) {
            return s.executeUpdate() == 1;
        }
    }

    public TicketApproval lock(Connection c, String tenantId, UUID proposalId) throws SQLException {
        return load(c, tenantId, proposalId, true);
    }

    TicketApproval read(Connection c, String tenantId, UUID proposalId) throws SQLException {
        return load(c, tenantId, proposalId, false);
    }

    private TicketApproval load(Connection c, String tenantId, UUID proposalId, boolean forUpdate) throws SQLException {
        requireTransaction(c);
        String sql = """
                SELECT requester_id, requester_session, requester_auth_version, operation_id, title,
                    description, parameter_sha256, expires_at, outcome, version, reviewer_id, reviewer_session,
                    reviewer_auth_version
                FROM lesson17.ticket_approval WHERE tenant_id = ? AND proposal_id = ?
                """ + (forUpdate ? "FOR UPDATE" : "");
        try (var s = statement(c, sql, tenantId, proposalId); var rows = s.executeQuery()) {
            if (!rows.next()) throw new SecurityFailure("APPROVAL_NOT_FOUND", 404);
            var requester = new ActorRef(rows.getString(1), tenantId, rows.getString(2), rows.getLong(3));
            var command = new TicketCommand(rows.getString(4), rows.getString(5), rows.getString(6));
            if (!command.fingerprint().equals(rows.getString(7))) throw new IllegalStateException("Stored ticket digest differs");
            var reviewerId = rows.getString(11);
            var reviewer = reviewerId == null ? null : new ActorRef(reviewerId, tenantId, rows.getString(12), rows.getLong(13));
            return TicketApproval.restore(proposalId, requester, reviewer, command, rows.getTimestamp(8).toInstant(),
                    TicketApproval.Outcome.valueOf(rows.getString(9)), rows.getLong(10));
        }
    }

    public void saveDecision(Connection c, TicketApproval reviewed) throws SQLException {
        requireTransaction(c);
        var reviewer = reviewed.reviewer();
        if (reviewer == null) throw new IllegalArgumentException("Reviewed proposal required");
        try (var s = statement(c, """
                UPDATE lesson17.ticket_approval
                SET outcome = ?, version = ?, reviewer_id = ?, reviewer_session = ?, reviewer_auth_version = ?
                WHERE tenant_id = ? AND proposal_id = ? AND version = ? AND outcome = 'PENDING'
                  AND operation_id = ? AND parameter_sha256 = ? AND requester_id = ?
                """, reviewed.outcome().name(), reviewed.version(), reviewer.subjectId(), reviewer.sessionId(),
                reviewer.authenticationVersion(), reviewer.tenantId(), reviewed.proposalId(), reviewed.version() - 1,
                reviewed.command().operationId(), reviewed.command().fingerprint(), reviewed.requester().subjectId())) {
            if (s.executeUpdate() != 1) throw new SecurityFailure("APPROVAL_CONFLICT", 409);
        }
    }

    public Ticket writeTicket(Connection c, TicketApproval proposal, ActorRef actor, long expectedVersion,
                              String operationId, String fingerprint, Instant now) throws SQLException {
        var stored = lock(c, actor.tenantId(), proposal.proposalId());
        var command = stored.commandForReceipt(actor, expectedVersion, operationId, fingerprint);
        try (var s = statement(c, """
                SELECT ticket_id FROM lesson17.approved_ticket WHERE tenant_id = ? AND proposal_id = ?
                """, actor.tenantId(), stored.proposalId()); var rows = s.executeQuery()) {
            if (rows.next()) return receipt(rows.getObject(1, UUID.class), command);
        }
        stored.commandForExecution(actor, expectedVersion, operationId, fingerprint, now);
        var ticketId = UUID.randomUUID();
        try (var s = statement(c, """
                INSERT INTO lesson17.approved_ticket (tenant_id, proposal_id, operation_id, ticket_id)
                VALUES (?, ?, ?, ?)
                """, actor.tenantId(), stored.proposalId(), operationId, ticketId)) {
            s.executeUpdate();
        }
        return receipt(ticketId, command);
    }

    private static Ticket receipt(UUID id, TicketCommand command) {
        return new Ticket("T-" + id, command.operationId(), command.title(), command.description());
    }

    private static void requireTransaction(Connection c) throws SQLException {
        if (c.getAutoCommit()) throw new IllegalArgumentException("Ticket storage requires a caller-owned transaction");
    }
}
