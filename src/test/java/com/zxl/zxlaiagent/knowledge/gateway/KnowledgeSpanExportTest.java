package com.zxl.zxlaiagent.knowledge.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class KnowledgeSpanExportTest {
    @Test
    void exportsChildAndErrorStatusWithoutBusinessData() throws Exception {
        var received = new ArrayBlockingQueue<byte[]>(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/traces", exchange -> {
            try (exchange) {
                received.offer(exchange.getRequestBody().readAllBytes());
                exchange.sendResponseHeaders(200, -1);
            }
        });
        server.start();
        try (var exporter = new KnowledgeSpanExport(URI.create(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/traces"))) {
            String parent = KnowledgeTrace.fromRun(UUID.randomUUID().toString());
            String child = KnowledgeTrace.child(parent);
            exporter.offer(KnowledgeSpanExport.Operation.MCP, child, parent, Instant.now(), true);
            byte[] body = received.poll(5, TimeUnit.SECONDS);
            assertNotNull(body);
            var resource = new ObjectMapper().readTree(body).path("resourceSpans").get(0);
            assertEquals("zxl-knowledge-mcp", resource.at("/resource/attributes/0/value/stringValue").asText());
            var span = resource.at("/scopeSpans/0/spans/0");
            assertEquals(child.substring(3, 35), span.path("traceId").asText());
            assertEquals(child.substring(36, 52), span.path("spanId").asText());
            assertEquals(parent.substring(36, 52), span.path("parentSpanId").asText());
            assertEquals(2, span.at("/status/code").asInt());
            assertFalse(span.has("attributes"));
        } finally {
            server.stop(0);
        }
    }
}
