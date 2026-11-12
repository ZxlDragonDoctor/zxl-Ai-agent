package com.zxl.zxlaiagent.ticket;

import com.zxl.zxlaiagent.security.ActorRef;
import com.zxl.zxlaiagent.security.SecurityFailure;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable ticket proposal. Callers own live permission checks and atomic persistence. */
public final class TicketApproval {
    public enum Outcome { PENDING, APPROVED, REJECTED }

    private final UUID proposalId;
    private final ActorRef requester;
    private final ActorRef reviewer;
    private final TicketCommand command;
    private final Instant expiresAt;
    private final Outcome outcome;
    private final long version;

    private TicketApproval(UUID proposalId, ActorRef requester, ActorRef reviewer, TicketCommand command,
                           Instant expiresAt, Outcome outcome, long version) {
        this.proposalId = proposalId;
        this.requester = requester;
        this.reviewer = reviewer;
        this.command = command;
        this.expiresAt = expiresAt;
        this.outcome = outcome;
        this.version = version;
    }

    public static TicketApproval propose(UUID proposalId, ActorRef requester, TicketCommand command, Instant expiresAt) {
        return new TicketApproval(Objects.requireNonNull(proposalId), Objects.requireNonNull(requester), null,
                Objects.requireNonNull(command), Objects.requireNonNull(expiresAt), Outcome.PENDING, 1);
    }

    public UUID proposalId() { return proposalId; }
    public long version() { return version; }
    public Outcome outcome() { return outcome; }
    ActorRef requester() { return requester; }
    ActorRef reviewer() { return reviewer; }
    TicketCommand command() { return command; }
    Instant expiresAt() { return expiresAt; }

    static TicketApproval restore(UUID id, ActorRef requester, ActorRef reviewer, TicketCommand command,
                                  Instant expiresAt, Outcome outcome, long version) {
        if (outcome == Outcome.PENDING ? version != 1 || reviewer != null : version != 2 || reviewer == null) {
            throw new IllegalStateException("Invalid stored approval state");
        }
        return new TicketApproval(id, requester, reviewer, command, expiresAt, outcome, version);
    }

    public TicketApproval review(ActorRef actor, Outcome decision, long expectedVersion, String operationId,
                                 String fingerprint, Instant now) {
        if (actor == null || !requester.tenantId().equals(actor.tenantId())
                || requester.subjectId().equals(actor.subjectId())) {
            throw new SecurityFailure("APPROVAL_REVIEWER_FORBIDDEN", 403);
        }
        requireBinding(expectedVersion, operationId, fingerprint, now);
        if (outcome != Outcome.PENDING) throw new SecurityFailure("APPROVAL_ALREADY_DECIDED", 409);
        if (decision != Outcome.APPROVED && decision != Outcome.REJECTED) {
            throw new SecurityFailure("APPROVAL_INVALID_DECISION", 400);
        }
        return new TicketApproval(proposalId, requester, actor, command, expiresAt, decision, version + 1);
    }

    public TicketCommand commandForExecution(ActorRef actor, long expectedVersion, String operationId,
                                             String fingerprint, Instant now) {
        var approved = commandForReceipt(actor, expectedVersion, operationId, fingerprint);
        requireCurrent(now);
        return approved;
    }

    TicketCommand commandForReceipt(ActorRef actor, long expectedVersion, String operationId, String fingerprint) {
        if (outcome != Outcome.APPROVED) throw new SecurityFailure("APPROVAL_NOT_GRANTED", 409);
        if (!reviewer.equals(actor)) throw new SecurityFailure("APPROVAL_REVIEWER_MISMATCH", 403);
        requireBinding(expectedVersion, operationId, fingerprint);
        return command;
    }

    private void requireBinding(long expectedVersion, String operationId, String fingerprint, Instant now) {
        requireCurrent(now);
        requireBinding(expectedVersion, operationId, fingerprint);
    }

    private void requireCurrent(Instant now) {
        if (!expiresAt.isAfter(Objects.requireNonNull(now))) throw new SecurityFailure("APPROVAL_EXPIRED", 409);
    }

    private void requireBinding(long expectedVersion, String operationId, String fingerprint) {
        if (version != expectedVersion || !command.operationId().equals(operationId)
                || !command.fingerprint().equals(fingerprint)) {
            throw new SecurityFailure("APPROVAL_BINDING_MISMATCH", 409);
        }
    }
}
