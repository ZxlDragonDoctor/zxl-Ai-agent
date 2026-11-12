package com.zxl.zxlaiagent.ticket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zxl.zxlaiagent.conversation.service.ConversationService;
import com.zxl.zxlaiagent.security.SecurityFailure;
import com.zxl.zxlaiagent.security.web.AuthenticatedRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/ai/learning/ticket-proposals")
@ConditionalOnProperty(name = "agent.security.mode", havingValue = "required")
public final class FormalTicketProposalController {
    private final ConversationService conversations;
    private final TicketApprovalWorkflow approvals;
    private final StoredSkillDraft drafts;

    public FormalTicketProposalController(ConversationService conversations, TicketApprovalWorkflow approvals,
                                          ObjectMapper mapper) {
        this.conversations = conversations;
        this.approvals = approvals;
        this.drafts = new StoredSkillDraft(mapper);
    }

    public record Source(UUID conversationId, UUID runId) {}
    public record Submitted(UUID proposalId, TicketApproval.Outcome outcome, String expiresAt) {}

    @PostMapping
    public Submitted submit(HttpServletRequest request, @RequestBody Source source) {
        var actor = AuthenticatedRequest.require(request).ref();
        if (source == null || source.conversationId() == null || source.runId() == null) {
            throw new SecurityFailure("TICKET_DRAFT_SOURCE_REQUIRED", 400);
        }
        var conversation = conversations.detail(actor, source.conversationId());
        var draft = drafts.resolve(conversation, source.runId());
        var proposal = approvals.proposeFromRun(actor, source.conversationId(), source.runId(), draft);
        return new Submitted(proposal.proposalId(), proposal.outcome(), proposal.expiresAt().toString());
    }
}
