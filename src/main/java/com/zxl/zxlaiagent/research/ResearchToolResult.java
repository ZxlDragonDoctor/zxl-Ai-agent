package com.zxl.zxlaiagent.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.zxl.zxlaiagent.knowledge.retrieval.RetrievalService.Stage;
import com.zxl.zxlaiagent.tools.model.ToolResult;
import com.zxl.zxlaiagent.tools.support.ToolResults;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Public tool event contract. Only independently verified hits enter the public payload. */
final class ResearchToolResult {
    private static final Set<String> STAGE_NAMES = Set.of(
            "index-profile", "rewrite", "vector", "keyword", "authorization-before-rerank", "rerank", "final-visibility");
    private static final Set<String> STAGE_STATES = Set.of(
            "OK", "FAILED", "DISABLED", "UNCHANGED", "DETERMINISTIC_LEXICAL_SUPPLEMENT", "DEGRADED");
    private static final Set<String> DEGRADATIONS = Set.of(
            "VECTOR_CHANNEL_FAILED", "KEYWORD_CHANNEL_FAILED", "RERANK_FAILED_OR_BUDGET_EXHAUSTED");
    private static final Set<String> REMOTE_CODES = Set.of("MCP_INVALID_ARGUMENT", "MCP_NOT_AUTHORIZED",
            "MCP_DEADLINE_EXCEEDED", "MCP_CANCELLED", "MCP_CAPACITY", "MCP_REMOTE_BUSINESS_FAILURE", "MCP_INVALID_RESPONSE");

    private ResearchToolResult() {}

    static String success(JsonNode data, List<JsonNode> verifiedHits) {
        Map<String, Object> visible = new LinkedHashMap<>();
        visible.put("hits", verifiedHits.stream().map(ResearchToolResult::projectHit).toList());
        var stages = new ArrayList<Stage>();
        for (JsonNode stage : data.path("stages")) {
            String name = stage.path("name").asText();
            String status = stage.path("status").asText();
            long elapsedMs = stage.path("elapsedMs").asLong(-1);
            int candidateCount = stage.path("candidateCount").asInt(-1);
            if (STAGE_NAMES.contains(name) && STAGE_STATES.contains(status)
                    && elapsedMs >= 0 && candidateCount >= 0 && stages.size() < STAGE_NAMES.size()) {
                stages.add(new Stage(name, status, elapsedMs, candidateCount));
            }
        }
        var degradations = new ArrayList<String>();
        for (JsonNode entry : data.path("degradations")) {
            String code = entry.asText();
            if (DEGRADATIONS.contains(code) && !degradations.contains(code)) degradations.add(code);
        }
        visible.put("stages", stages);
        visible.put("degradations", degradations);
        long elapsedMs = data.path("elapsedMs").asLong(-1);
        if (elapsedMs >= 0) visible.put("elapsedMs", elapsedMs);
        return ToolResults.json(ToolResult.ok("知识查询完成 / Knowledge lookup completed", visible));
    }

    static String remoteFailure(JsonNode envelope) {
        String code = envelope.path("data").path("remoteCode").asText();
        String message = "MCP 服务未完成查询 / MCP lookup failed";
        var details = "MCP_TOOL_ERROR".equals(envelope.path("code").asText()) && REMOTE_CODES.contains(code)
                ? Map.of("remoteCode", code) : null;
        return ToolResults.json(ToolResult.fail("MCP_TOOL_ERROR", message, details));
    }

    private static Map<String, Object> projectHit(JsonNode hit) {
        return Map.of("chunkId", hit.path("chunkId").asText(), "documentId", hit.path("documentId").asText(),
                "revisionId", hit.path("revisionId").asText(), "generation", hit.path("generation").asLong(),
                "text", hit.path("text").asText(), "startLine", hit.path("startLine").asInt(),
                "endLine", hit.path("endLine").asInt(), "startUtf16", hit.path("startUtf16").asInt(),
                "endUtf16", hit.path("endUtf16").asInt());
    }
}
