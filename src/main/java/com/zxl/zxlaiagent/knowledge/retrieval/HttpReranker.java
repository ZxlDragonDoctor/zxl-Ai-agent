package com.zxl.zxlaiagent.knowledge.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Explicit DashScope rerank protocol. No external text or IDs can enter the evidence set.
 * Wire reference: https://www.alibabacloud.com/help/en/model-studio/text-rerank-api
 * Endpoint/model/deployment revision must be supplied by the operator, never by a query.
 */
public final class HttpReranker implements Reranker {
    private final URI endpoint;
    private final String key, model, identity;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public HttpReranker(URI endpoint, String key, String model, String revision) {
        if (endpoint == null || endpoint.getHost() == null || endpoint.getUserInfo() != null || endpoint.getQuery() != null
                || !("https".equals(endpoint.getScheme()) || ("http".equals(endpoint.getScheme()) && "127.0.0.1".equals(endpoint.getHost())))) {
            throw new IllegalArgumentException("Reranker requires HTTPS; HTTP is restricted to loopback contract tests");
        }
        if (key == null || key.isBlank() || model == null || !model.matches("[a-zA-Z0-9._-]{1,45}")
                || revision == null || !revision.matches("[a-zA-Z0-9._-]{1,32}")) {
            throw new IllegalArgumentException("Explicit reranker key, model and deployment revision required");
        }
        this.endpoint = endpoint; this.key = key; this.model = model;
        this.identity = "http-rerank:" + model + ":" + revision;
    }
    @Override public String identity() { return identity; }

    @Override public List<Score> rank(String originalQuery, List<RetrievalService.Hit> candidates, Duration remaining) {
        if (remaining == null || remaining.isNegative() || remaining.isZero()) throw new IllegalArgumentException("Reranking budget exhausted");
        if (originalQuery == null || originalQuery.isBlank() || originalQuery.length() > 2000 || candidates == null || candidates.size() > 20) throw new IllegalArgumentException("Reranking input exceeds lesson limits");
        if (candidates.isEmpty()) return List.of();
        if (candidates.stream().mapToInt(hit -> hit.text().length()).sum() > 20000) throw new IllegalArgumentException("Reranker text budget exceeded; no implicit truncation");
        try {
            String body = json.writeValueAsString(Map.of("model", model,
                    "input", Map.of("query", originalQuery, "documents", candidates.stream().map(RetrievalService.Hit::text).toList()),
                    "parameters", Map.of("top_n", candidates.size(), "return_documents", false)));
            var request = HttpRequest.newBuilder(endpoint).timeout(remaining)
                    .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IllegalStateException("Reranker provider HTTP " + response.statusCode());
            if (response.body().length() > 1024 * 1024) throw new IllegalStateException("Reranker response exceeds 1 MiB");
            var results = json.readTree(response.body()).path("output").path("results");
            if (!results.isArray() || results.size() != candidates.size()) throw new IllegalStateException("Incomplete reranker scores");
            List<Score> scores = new ArrayList<>();
            for (var entry : results) {
                var index = entry.path("index"); var relevance = entry.path("relevance_score");
                if (!index.isIntegralNumber() || !index.canConvertToInt() || index.intValue() < 0 || index.intValue() >= candidates.size()
                        || !relevance.isNumber() || !Double.isFinite(relevance.asDouble()) || relevance.asDouble() < 0 || relevance.asDouble() > 1) {
                    throw new IllegalStateException("Invalid reranker index or score");
                }
                scores.add(new Score(candidates.get(index.intValue()).chunkId(), relevance.asDouble()));
            }
            Reranker.validate(candidates, scores);
            return List.copyOf(scores);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("Reranking interrupted", interrupted);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Reranker HTTP operation failed", failure);
        }
    }
}
