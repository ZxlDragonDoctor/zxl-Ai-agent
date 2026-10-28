package com.zxl.zxlaiagent.conversation.controller;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.zxl.zxlaiagent.agent.stream.PreparedAgentStream;
import com.zxl.zxlaiagent.agent.stream.PreparedAgentStreamInterceptor;
import com.zxl.zxlaiagent.conversation.ConversationException;
import com.zxl.zxlaiagent.conversation.agent.AgentRunOptions;
import com.zxl.zxlaiagent.conversation.context.ContextAssembler;
import com.zxl.zxlaiagent.conversation.service.AgentConversationService;
import com.zxl.zxlaiagent.security.SecurityFailure;
import com.zxl.zxlaiagent.security.web.AuthenticatedRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.*;
import java.util.function.Supplier;

/** Formal unified chat entry. Identity and provider selection are resolved by trusted Java code. */
@RestController
@ConditionalOnProperty(name = "agent.security.mode", havingValue = "required")
public final class FormalAgentChatController {
    private static final Set<String> CHAT_FIELDS = Set.of("message", "conversationId", "skillId", "skillVersion");
    private static final Set<String> PREVIEW_FIELDS = Set.of("message", "conversationId");
    private static final int MAX_BODY_BYTES = 65536;
    private final AgentConversationService service;
    private final ObjectMapper json = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public FormalAgentChatController(AgentConversationService service) { this.service = service; }

    @PostMapping(value = {"/ai/agent/chat", "/ai/manus/chat"},
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public PreparedAgentStream chat(HttpServletRequest request) {
        var actor = AuthenticatedRequest.require(request).ref();
        JsonNode body = read(request, CHAT_FIELDS);
        String message = message(body), conversation = text(body, "conversationId");
        return safe(() -> {
            var options = new AgentRunOptions(text(body, "skillId"), text(body, "skillVersion"));
            var prepared = service.prepareChat(actor, message, conversation, options);
            return PreparedAgentStreamInterceptor.attach(request, prepared);
        });
    }

    public record Preview(ContextAssembler.Summary summary, ContextAssembler.Report budget) {}

    @PostMapping(value = "/ai/learning/context/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Preview preview(HttpServletRequest request) {
        var actor = AuthenticatedRequest.require(request).ref();
        JsonNode body = read(request, PREVIEW_FIELDS);
        String message = message(body), conversation = text(body, "conversationId");
        if (conversation == null || conversation.isBlank()) throw invalid();
        return safe(() -> {
            var plan = service.previewContext(actor, conversation, message);
            return new Preview(plan.summary(), plan.report());
        });
    }

    private JsonNode read(HttpServletRequest request, Set<String> allowed) {
        try {
            byte[] bytes = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length == 0 || bytes.length > MAX_BODY_BYTES) throw invalid();
            JsonNode body = json.readTree(bytes);
            if (body == null || !body.isObject()) throw invalid();
            for (Iterator<String> fields = body.fieldNames(); fields.hasNext(); ) {
                if (!allowed.contains(fields.next())) throw invalid();
            }
            return body;
        } catch (IOException malformed) { throw invalid(); }
    }

    private static String text(JsonNode body, String field) {
        var value = body.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw invalid();
        return value.textValue();
    }

    private static String message(JsonNode body) {
        String value = text(body, "message");
        if (value == null || value.isBlank() || value.length() > 12000) throw invalid();
        return value;
    }

    private static SecurityFailure invalid() { return new SecurityFailure("INVALID_AGENT_REQUEST", 400); }

    private static <T> T safe(Supplier<T> operation) {
        try { return operation.get(); }
        catch (SecurityFailure failure) {
            if ("RESOURCE_UNAVAILABLE".equals(failure.code())) throw new SecurityFailure("NOT_FOUND", 404);
            throw failure;
        } catch (ConversationException failure) {
            throw new SecurityFailure(failure.getStatus().value() == 404 ? "NOT_FOUND" : failure.getCode(), failure.getStatus().value());
        } catch (IllegalArgumentException invalidOptionsOrBudget) { throw invalid(); }
    }
}
