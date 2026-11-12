package com.zxl.zxlaiagent.ticket;

import com.zxl.zxlaiagent.security.ActorRef;
import com.zxl.zxlaiagent.security.AuthorizationService;
import com.zxl.zxlaiagent.security.Phase;
import com.zxl.zxlaiagent.security.SecurityDatabase;
import com.zxl.zxlaiagent.security.execution.OperationBoundary;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Application service for trusted ticket proposal callers and authenticated reviewers. */
public final class TicketApprovalWorkflow {
    private static final Duration REVIEW_WINDOW = Duration.ofMinutes(15);

    private final SecurityDatabase database;
    private final AuthorizationService authorization;
    private final TicketApprovalStore store;
    private final Clock clock;

    public TicketApprovalWorkflow(SecurityDatabase database, AuthorizationService authorization,
                                  TicketApprovalStore store, Clock clock) {
        this.database = Objects.requireNonNull(database);
        this.authorization = Objects.requireNonNull(authorization);
        this.store = Objects.requireNonNull(store);
        this.clock = Objects.requireNonNull(clock);
    }

    /** The caller must first resolve the draft from the requester's authorized stored history. */
    TicketApproval propose(ActorRef requester, String title, String description) {
        UUID proposalId = UUID.randomUUID();
        return inTransaction(requester, "ticket.propose", proposalId, connection -> {
            var command = new TicketCommand(proposalId.toString(), title, description);
            var proposal = TicketApproval.propose(proposalId, requester, command, clock.instant().plus(REVIEW_WINDOW));
            store.insert(connection, proposal);
            return proposal;
        });
    }

    public TicketApproval review(ActorRef reviewer, UUID proposalId, TicketApproval.Outcome decision,
                                 long version, String operationId, String fingerprint) {
        return inTransaction(reviewer, "ticket.review", proposalId, connection -> {
            var proposal = store.lock(connection, reviewer.tenantId(), proposalId);
            var reviewed = proposal.review(reviewer, decision, version, operationId, fingerprint, clock.instant());
            store.saveDecision(connection, reviewed);
            return reviewed;
        });
    }

    public Ticket execute(ActorRef reviewer, UUID proposalId, long version, String operationId, String fingerprint) {
        return inTransaction(reviewer, "ticket.execute", proposalId, connection -> {
            var proposal = store.lock(connection, reviewer.tenantId(), proposalId);
            return store.writeTicket(connection, proposal, reviewer, version, operationId, fingerprint, clock.instant());
        });
    }

    private <T> T inTransaction(ActorRef actor, String action, UUID proposalId, OperationBoundary.SqlWork<T> work) {
        return database.tx(connection -> authorization.sqlGate(connection, actor,
                List.of(new AuthorizationService.Permission(action, null)), Phase.PERSIST, proposalId.toString(), work)).result();
    }
}
