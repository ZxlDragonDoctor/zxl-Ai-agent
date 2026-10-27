package com.zxl.zxlaiagent.conversation.controller;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.zxl.zxlaiagent.conversation.ConversationException;
import com.zxl.zxlaiagent.conversation.model.*;
import com.zxl.zxlaiagent.conversation.service.ConversationService;
import com.zxl.zxlaiagent.security.*;
import com.zxl.zxlaiagent.security.web.AuthenticatedRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.*;
import java.util.function.Supplier;

/** Formal entry accepts identity exclusively from the verified request context. */
@RestController
@RequestMapping("/conversations")
@ConditionalOnProperty(name = "agent.security.mode", havingValue = "required")
public final class FormalConversationController {
    private static final Set<String> CREATE_FIELDS = Set.of("title", "agentType");
    private static final int MAX_BODY_BYTES = 4096;
    private final ConversationService service;
    private final ObjectMapper json = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public FormalConversationController(ConversationService service) { this.service = service; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationDetail create(HttpServletRequest request) {
        ActorRef actor = AuthenticatedRequest.require(request).ref();
        JsonNode body = readCreateBody(request);
        String title = optionalText(body, "title");
        String type = optionalText(body, "agentType");
        return scoped(() -> {
            Conversation conversation = service.create(actor, title, type);
            return service.detail(actor, conversation.id());
        });
    }

    @GetMapping
    public List<ConversationSummary> list(HttpServletRequest request) {
        ActorRef actor = AuthenticatedRequest.require(request).ref();
        return scoped(() -> service.list(actor));
    }

    @GetMapping("/{id}")
    public ConversationDetail get(@PathVariable String id, HttpServletRequest request) {
        ActorRef actor = AuthenticatedRequest.require(request).ref();
        return scoped(() -> service.detail(actor, ConversationService.parseId(id)));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id, HttpServletRequest request) {
        ActorRef actor = AuthenticatedRequest.require(request).ref();
        scoped(() -> { service.delete(actor, ConversationService.parseId(id)); return null; });
    }

    private JsonNode readCreateBody(HttpServletRequest request) {
        try {
            byte[] bytes = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) throw invalidBody();
            if (bytes.length == 0) return json.createObjectNode();
            JsonNode body = json.readTree(bytes);
            if (body == null || !body.isObject()) throw invalidBody();
            for (Iterator<String> fields = body.fieldNames(); fields.hasNext(); ) {
                if (!CREATE_FIELDS.contains(fields.next())) throw invalidBody();
            }
            return body;
        } catch (IOException invalidJson) { throw invalidBody(); }
    }

    private static String optionalText(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw invalidBody();
        return value.textValue();
    }

    private static SecurityFailure invalidBody() { return new SecurityFailure("INVALID_CONVERSATION_REQUEST", 400); }

    /** A stale/foreign owner must be indistinguishable from a missing UUID at HTTP boundaries. */
    private static <T> T scoped(Supplier<T> operation) {
        try { return operation.get(); }
        catch (SecurityFailure failure) {
            if ("RESOURCE_UNAVAILABLE".equals(failure.code())) throw new SecurityFailure("NOT_FOUND", 404);
            throw failure;
        } catch (ConversationException failure) {
            if (failure.getStatus() == HttpStatus.NOT_FOUND) throw new SecurityFailure("NOT_FOUND", 404);
            throw new SecurityFailure(failure.getCode(), failure.getStatus().value());
        }
    }
}
