package com.zxl.zxlaiagent.ticket;

import com.zxl.zxlaiagent.security.ActorRef;
import com.zxl.zxlaiagent.security.SecurityFailure;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TicketApprovalTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final ActorRef AUTHOR = new ActorRef("author", "tenant", "author-session", 1);
    private static final ActorRef REVIEWER = new ActorRef("reviewer", "tenant", "review-session", 2);
    private final TicketCommand command = new TicketCommand("operation", "VPN 401", "Restore access");
    private final TicketApproval proposal = TicketApproval.propose(UUID.randomUUID(), AUTHOR, command, NOW.plusSeconds(60));

    @Test void approvedCommandIsBoundToOperationDigestVersionAndReviewer() {
        var approved = proposal.review(REVIEWER, TicketApproval.Outcome.APPROVED,
                1, command.operationId(), command.fingerprint(), NOW);
        assertEquals(2, approved.version());
        assertEquals(TicketApproval.Outcome.PENDING, proposal.outcome());
        assertEquals(command, approved.commandForExecution(REVIEWER, 2, command.operationId(), command.fingerprint(), NOW));
        assertCode("APPROVAL_BINDING_MISMATCH",
                () -> approved.commandForExecution(REVIEWER, 2, "other-operation", command.fingerprint(), NOW));
        assertCode("APPROVAL_BINDING_MISMATCH",
                () -> approved.commandForExecution(REVIEWER, 2, command.operationId(),
                        new TicketCommand("operation", "Changed title", "Restore access").fingerprint(), NOW));
        assertCode("APPROVAL_REVIEWER_MISMATCH",
                () -> approved.commandForExecution(new ActorRef("reviewer", "tenant", "new-session", 3),
                        2, command.operationId(), command.fingerprint(), NOW));
    }

    @Test void selfReviewAndForeignReviewAreRejected() {
        assertCode("APPROVAL_REVIEWER_FORBIDDEN", () -> reviewAs(AUTHOR, NOW));
        assertCode("APPROVAL_REVIEWER_FORBIDDEN", () -> reviewAs(new ActorRef("reviewer", "other", "s", 1), NOW));
    }

    @Test void rejectionExpiryAndStaleDecisionsCannotYieldACommand() {
        assertCode("APPROVAL_EXPIRED", () -> reviewAs(REVIEWER, NOW.plusSeconds(60)));
        var rejected = proposal.review(REVIEWER, TicketApproval.Outcome.REJECTED,
                1, command.operationId(), command.fingerprint(), NOW);
        assertCode("APPROVAL_NOT_GRANTED",
                () -> rejected.commandForExecution(REVIEWER, 2, command.operationId(), command.fingerprint(), NOW));
        assertCode("APPROVAL_BINDING_MISMATCH",
                () -> rejected.review(REVIEWER, TicketApproval.Outcome.APPROVED,
                        1, command.operationId(), command.fingerprint(), NOW));
        var approved = reviewAs(REVIEWER, NOW);
        assertCode("APPROVAL_EXPIRED",
                () -> approved.commandForExecution(REVIEWER, 2, command.operationId(), command.fingerprint(), NOW.plusSeconds(60)));
    }

    private TicketApproval reviewAs(ActorRef reviewer, Instant now) {
        return proposal.review(reviewer, TicketApproval.Outcome.APPROVED, 1, command.operationId(), command.fingerprint(), now);
    }

    private static void assertCode(String code, Runnable action) {
        assertEquals(code, assertThrows(SecurityFailure.class, action::run).code());
    }
}
