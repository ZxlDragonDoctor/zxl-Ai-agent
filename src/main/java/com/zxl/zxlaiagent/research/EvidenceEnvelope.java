package com.zxl.zxlaiagent.research;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Fixed projection of evidence already revalidated by ResearchRunner. */
record EvidenceEnvelope(String sourceKind, String sourceId, String documentId, String revisionId,
                        int startLine, int endLine, String contentSha256, boolean truncated, String text) {

    static EvidenceEnvelope verifiedChunk(JsonNode hit) {
        String text = hit.path("text").asText();
        return new EvidenceEnvelope("VERIFIED_KNOWLEDGE_CHUNK", hit.path("chunkId").asText(),
                hit.path("documentId").asText(), hit.path("revisionId").asText(),
                hit.path("startLine").asInt(), hit.path("endLine").asInt(), sha256(text), false, text);
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }
}
