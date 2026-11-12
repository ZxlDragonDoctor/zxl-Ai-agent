package com.zxl.zxlaiagent.ticket;

import com.zxl.zxlaiagent.security.ActorRef;
import com.zxl.zxlaiagent.security.AuthorizationService;
import com.zxl.zxlaiagent.security.Phase;
import com.zxl.zxlaiagent.security.ResourceRef;
import com.zxl.zxlaiagent.security.SecurityDatabase;
import com.zxl.zxlaiagent.security.execution.OperationBoundary;

import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
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
    TicketApproval proposeFromRun(ActorRef requester, UUID conversationId, UUID runId, StoredSkillDraft.Draft draft) {
        var permissions = List.of(
                new AuthorizationService.Permission("ticket.propose", null),
                new AuthorizationService.Permission("conversation.readOwn", new ResourceRef("conversation", conversationId.toString())),
                new AuthorizationService.Permission("run.readOwn", new ResourceRef("run", runId.toString())));
        return database.tx(connection -> authorization.sqlGate(connection, requester, permissions, Phase.PERSIST,
                runId.toString(), sql -> {
                    var command = new TicketCommand(runId.toString(), draft.title(), draft.problem());
                    var expiresAt = clock.instant().plus(REVIEW_WINDOW).truncatedTo(ChronoUnit.MILLIS);
                    var proposal = TicketApproval.propose(runId, requester, command, expiresAt);
                    return store.insertOrReplay(sql, proposal);
                })).result();
    }

    public TicketApproval inspect(ActorRef reviewer, UUID proposalId) {
        return database.tx(connection -> authorization.sqlGate(connection, reviewer,
                List.of(new AuthorizationService.Permission("ticket.review", null)),
                Phase.SOURCE_READ, proposalId.toString(), sql -> store.read(sql, reviewer.tenantId(), proposalId))).result();
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
