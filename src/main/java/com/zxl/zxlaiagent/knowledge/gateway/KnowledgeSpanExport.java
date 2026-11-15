package com.zxl.zxlaiagent.knowledge.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Optional best-effort OTLP/HTTP export. Export never waits on a business thread. */
public final class KnowledgeSpanExport implements AutoCloseable {
    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(KnowledgeSpanExport.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private final URI endpoint;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    private final ThreadPoolExecutor queue = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64), task -> {
        var thread = new Thread(task, "knowledge-otlp");
        thread.setDaemon(true);
        return thread;
    }, new ThreadPoolExecutor.DiscardPolicy());

    public enum Operation {
        APPLICATION("zxl-agent", "knowledge.mcp.call", 3),
        MCP("zxl-knowledge-mcp", "knowledge.mcp.lookup", 2),
        WORKER("zxl-knowledge-worker", "knowledge.worker.request", 2);

        final String service;
        final String name;
        final int kind;

        Operation(String service, String name, int kind) {
            this.service = service;
            this.name = name;
            this.kind = kind;
        }
    }

    private static final class Configured {
        static final KnowledgeSpanExport INSTANCE = load();
        private static KnowledgeSpanExport load() {
            try {
                String property = System.getProperty("agent.knowledge.otlp-traces-endpoint",
                        System.getenv("AGENT_KNOWLEDGE_OTLP_TRACES_ENDPOINT"));
                return property == null || property.isBlank() ? null : new KnowledgeSpanExport(URI.create(property));
            } catch (RuntimeException invalid) {
                LOG.warn("Knowledge span export configuration is invalid");
                return null;
            }
        }
    }

    KnowledgeSpanExport(URI endpoint) {
        if (!("http".equals(endpoint.getScheme()) || "https".equals(endpoint.getScheme()))
                || !Set.of("127.0.0.1", "localhost", "[::1]").contains(String.valueOf(endpoint.getHost()))
                || !"/v1/traces".equals(endpoint.getPath()) || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null || endpoint.getFragment() != null) {
            throw new IllegalArgumentException("A local OTLP /v1/traces endpoint is required");
        }
        this.endpoint = endpoint;
        queue.allowCoreThreadTimeOut(true);
    }

    public static void finished(Operation operation, String span, String parent, Instant start, boolean failed) {
        if (span == null) return;
        var exporter = Configured.INSTANCE;
        if (exporter != null) exporter.offer(operation, span, parent, start, failed);
    }

    void offer(Operation operation, String span, String parent, Instant start, boolean failed) {
        if (KnowledgeTrace.valid(span) == null || !span.endsWith("-01")) return;
        if (parent != null && (KnowledgeTrace.valid(parent) == null
                || !span.regionMatches(3, parent, 3, 32))) return;
        Instant end = Instant.now();
        queue.execute(() -> send(operation, span, parent, start, end.isBefore(start) ? start : end, failed));
    }

    private void send(Operation operation, String span, String parent, Instant start, Instant end, boolean failed) {
        try {
            var payload = Map.of("resourceSpans", List.of(Map.of(
                    "resource", Map.of("attributes", List.of(Map.of("key", "service.name",
                            "value", Map.of("stringValue", operation.service)))),
                    "scopeSpans", List.of(Map.of("scope", Map.of("name", "zxl.knowledge"),
                            "spans", List.of(Map.of("traceId", span.substring(3, 35),
                                    "spanId", span.substring(36, 52), "parentSpanId", parent == null ? "" : parent.substring(36, 52),
                                    "name", operation.name, "kind", operation.kind,
                                    "startTimeUnixNano", timestamp(start), "endTimeUnixNano", timestamp(end),
                                    "status", Map.of("code", failed ? 2 : 0))))))));
            var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(2))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(payload))).build();
            var response = http.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() != 200) LOG.debug("Knowledge span export HTTP status {}", response.statusCode());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception failedExport) {
            LOG.debug("Knowledge span export failed ({})", failedExport.getClass().getSimpleName());
        }
    }

    private static String timestamp(Instant instant) {
        return Long.toString(instant.getEpochSecond() * 1_000_000_000L + instant.getNano());
    }

    @Override public void close() {
        queue.shutdownNow();
    }
}
