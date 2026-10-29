package com.zxl.zxlaiagent.knowledge.mcp;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.zxl.zxlaiagent.knowledge.gateway.ScopedKnowledgeClient;
import com.zxl.zxlaiagent.security.*;
import java.net.URI;
import java.time.Duration;
import java.util.List;

/** Independent authenticated MCP entry; knowledge SQL remains owned by the scoped HTTP worker. */
public final class FormalKnowledgeMcpWorker {
    private FormalKnowledgeMcpWorker() {}
    public static FormalKnowledgeMcpTool.Lookup lookup(ScopedKnowledgeClient knowledge) {
        var json = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        return (actor, request) -> {
            final byte[] body;
            try { body = json.writeValueAsBytes(request); }
            catch (java.io.IOException invalid) { throw new IllegalStateException("KNOWLEDGE_REQUEST_INVALID"); }
            var response = knowledge.exchange(actor, "POST", "/ai/learning/knowledge/retrieval", null, body,
                    Duration.ofMillis(request.budgetMs()), Thread.currentThread()::isInterrupted, List.of(), null);
            if (response.status() == 401 || response.status() == 403 || response.status() == 404)
                throw new SecurityFailure("KNOWLEDGE_NOT_AUTHORIZED", response.status());
            if (response.status() != 200) throw new IllegalStateException("KNOWLEDGE_WORKER_UNAVAILABLE");
            try {
                var result = json.readTree(response.body());
                if (result == null || !result.isObject() || !result.path("hits").isArray())
                    throw new IllegalStateException("KNOWLEDGE_WORKER_INVALID_RESPONSE");
                return result;
            } catch (java.io.IOException invalid) { throw new IllegalStateException("KNOWLEDGE_WORKER_INVALID_RESPONSE"); }
        };
    }
    public static void main(String[] args) {
        String token = System.getenv("LESSON17_INTERNAL_SERVICE_TOKEN");
        var authority = SecurityDatabase.fromEnvironment();
        var authorization = new AuthorizationService(authority);
        var tickets = new ServiceCallAuthorizer(authority, authorization);
        var knowledge = new ScopedKnowledgeClient(URI.create(System.getenv().getOrDefault(
                "LESSON17_KNOWLEDGE_WORKER_ORIGIN", "http://127.0.0.1:18132")), token, tickets, authorization);
        int port = Integer.parseInt(System.getenv().getOrDefault("LESSON17_MCP_PORT", "18135"));
        var server = FormalKnowledgeMcpServer.start(port, new FormalKnowledgeMcpTool(tickets, authorization, lookup(knowledge)), token);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "authorized-mcp-shutdown"));
    }
}
