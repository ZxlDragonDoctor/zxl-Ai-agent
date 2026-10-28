package com.zxl.zxlaiagent.knowledge.gateway;

import com.zxl.zxlaiagent.knowledge.scoped.WorkerRequestContract;
import com.zxl.zxlaiagent.security.*;
import com.zxl.zxlaiagent.security.web.AuthenticatedRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;

/** Browser entry owns authentication; only the client constructs internal credentials and tickets. */
@RestController
@RequestMapping(WorkerRequestContract.BASE)
@ConditionalOnProperty(name = "agent.security.mode", havingValue = "required")
public final class FormalKnowledgeGatewayController {
    private final ScopedKnowledgeClient client;
    public FormalKnowledgeGatewayController(ScopedKnowledgeClient client) { this.client = client; }

    @RequestMapping(value = {"/status", "/documents", "/documents/{id}", "/search", "/retrieval", "/answers",
            "/memories", "/memories/recall", "/memories/proposals", "/memories/{id}",
            "/memories/{id}/confirm", "/memories/{id}/reject"})
    public ResponseEntity<byte[]> exchange(HttpServletRequest request) {
        ActorRef actor = AuthenticatedRequest.require(request).ref();
        final byte[] body;
        try {
            body = request.getInputStream().readNBytes(ScopedKnowledgeClient.MAX_REQUEST_BYTES + 1);
        } catch (IOException malformed) { throw new SecurityFailure("INVALID_KNOWLEDGE_REQUEST", 400); }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        var response = client.exchange(actor, request.getMethod(), path, request.getQueryString(), body, Duration.ofSeconds(30));
        return ResponseEntity.status(response.status()).contentType(MediaType.APPLICATION_JSON).body(response.body());
    }

    @ExceptionHandler(SecurityFailure.class)
    public ResponseEntity<Map<String, String>> denied(SecurityFailure failure) {
        return ResponseEntity.status(failure.status()).body(Map.of("code", failure.code(), "message", failure.code()));
    }
}
