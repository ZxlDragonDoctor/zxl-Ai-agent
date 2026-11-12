package com.zxl.zxlaiagent.ticket;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.zxl.zxlaiagent.agent.model.AgentEndReason;
import com.zxl.zxlaiagent.conversation.model.ConversationDetail;
import com.zxl.zxlaiagent.security.SecurityFailure;

import java.util.UUID;

/** Reads the structured terminal event from authorized, non-archived conversation history. */
final class StoredSkillDraft {
    record Draft(String title, String problem) {}

    private final ObjectReader reader;

    StoredSkillDraft(ObjectMapper mapper) {
        this.reader = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    Draft resolve(ConversationDetail conversation, UUID runId) {
        if (conversation.readOnly() || !"it-ticket-skill".equals(conversation.agentType())) throw unavailable();
        var run = conversation.runs().stream().filter(item -> item.runId().equals(runId))
                .findFirst().orElseThrow(StoredSkillDraft::unavailable);
        if (run.endReason() != AgentEndReason.ANSWER_READY) throw unavailable();
        var terminal = run.events().stream().filter(event -> "SKILL_TERMINAL".equals(event.type())).toList();
        if (terminal.size() != 1) throw unavailable();
        var event = terminal.get(0);
        if (!"succeeded".equals(event.status()) || !runId.toString().equals(event.runId())
                || !conversation.id().toString().equals(event.conversationId()) || event.content() == null) {
            throw unavailable();
        }
        try {
            var result = reader.readTree(event.content());
            var snapshot = result.path("snapshot");
            var definition = snapshot.path("definition");
            var draft = result.path("draft");
            String schema = switch (definition.path("version").asText()) {
                case "v3" -> "ticket-draft-v1";
                case "v4" -> "ticket-draft-v2";
                default -> "";
            };
            if (!"SUCCEEDED".equals(result.path("status").asText()) || !"OK".equals(result.path("code").asText())
                    || !runId.toString().equals(snapshot.path("runId").asText())
                    || !"authenticated-skill-executor-v1".equals(snapshot.path("executorVersion").asText())
                    || !"it-ticket-analysis".equals(definition.path("skillId").asText())
                    || schema.isEmpty() || !schema.equals(definition.path("outputSchemaVersion").asText())
                    || !schema.equals(draft.path("schemaVersion").asText()) || !draft.path("created").isBoolean()
                    || draft.path("created").booleanValue() || !draft.path("requiresApproval").booleanValue()) {
                throw unavailable();
            }
            return new Draft(requiredText(draft.path("title").textValue(), 200),
                    requiredText(draft.path("problem").textValue(), 4000));
        } catch (JsonProcessingException invalid) {
            throw unavailable();
        }
    }

    private static String requiredText(String value, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) throw unavailable();
        return value;
    }

    private static SecurityFailure unavailable() {
        return new SecurityFailure("TICKET_DRAFT_UNAVAILABLE", 404);
    }
}
