package com.zxl.zxlaiagent.ticket;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zxl.zxlaiagent.security.SecurityFailure;
import com.zxl.zxlaiagent.security.web.AuthenticatedRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/ai/learning/ticket-approvals")
@ConditionalOnProperty(name = "agent.security.mode", havingValue = "required")
public final class FormalTicketApprovalController {
    private static final int MAX_BODY_BYTES = 4096;
    private static final Set<String> REVIEW_FIELDS = Set.of("decision", "version", "operationId", "fingerprint");
    private static final Set<String> EXECUTE_FIELDS = Set.of("version", "operationId", "fingerprint");
    private static final Pattern FINGERPRINT = Pattern.compile("[a-f0-9]{64}");
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final TicketApprovalWorkflow workflow;

    public FormalTicketApprovalController(TicketApprovalWorkflow workflow) {
        this.workflow = workflow;
    }

    @GetMapping("/{id}")
    public ApprovalDetails inspect(@PathVariable("id") UUID id, HttpServletRequest request) {
        var proposal = workflow.inspect(AuthenticatedRequest.require(request).ref(), id);
        var command = proposal.command();
        return new ApprovalDetails(proposal.proposalId(), command.title(), command.description(),
                proposal.version(), command.operationId(), command.fingerprint(), proposal.expiresAt().toString(), proposal.outcome());
    }

    @PostMapping(path = "/{id}/review", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ReviewResult review(@PathVariable("id") UUID id, HttpServletRequest request) {
        var actor = AuthenticatedRequest.require(request).ref();
        JsonNode body = readBody(request, REVIEW_FIELDS);
        var binding = binding(body);
        var decision = switch (body.get("decision").asText()) {
            case "APPROVED" -> TicketApproval.Outcome.APPROVED;
            case "REJECTED" -> TicketApproval.Outcome.REJECTED;
            default -> throw invalidRequest();
        };
        var result = workflow.review(actor, id, decision, binding.version(), binding.operationId(), binding.fingerprint());
        return new ReviewResult(result.proposalId(), result.version(), result.outcome());
    }

    @PostMapping(path = "/{id}/execute", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Ticket execute(@PathVariable("id") UUID id, HttpServletRequest request) {
        var actor = AuthenticatedRequest.require(request).ref();
        var binding = binding(readBody(request, EXECUTE_FIELDS));
        return workflow.execute(actor, id, binding.version(), binding.operationId(), binding.fingerprint());
    }

    private static JsonNode readBody(HttpServletRequest request, Set<String> fields) {
        try {
            byte[] bytes = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) throw invalidRequest();
            JsonNode body = JSON.readTree(bytes);
            if (body == null || !body.isObject() || body.size() != fields.size()
                    || fields.stream().anyMatch(field -> !body.hasNonNull(field))) {
                throw invalidRequest();
            }
            return body;
        } catch (IOException failure) {
            throw invalidRequest();
        }
    }

    private static Binding binding(JsonNode body) {
        JsonNode version = body.get("version");
        String operationId = body.get("operationId").textValue();
        String fingerprint = body.get("fingerprint").textValue();
        if (!version.isIntegralNumber() || !version.canConvertToLong() || version.longValue() < 1
                || operationId == null || operationId.isBlank() || operationId.length() > 128
                || fingerprint == null || !FINGERPRINT.matcher(fingerprint).matches()) {
            throw invalidRequest();
        }
        return new Binding(version.longValue(), operationId, fingerprint);
    }

    private static SecurityFailure invalidRequest() {
        return new SecurityFailure("INVALID_APPROVAL_REQUEST", 400);
    }

    private record Binding(long version, String operationId, String fingerprint) {}
    public record ApprovalDetails(UUID proposalId, String title, String description, long version, String operationId,
                                  String fingerprint, String expiresAt, TicketApproval.Outcome outcome) {}
    public record ReviewResult(UUID proposalId, long version, TicketApproval.Outcome outcome) {}
}
