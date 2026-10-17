package com.zxl.zxlaiagent.tools.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Parse the declared contract, rather than inferring success from natural-language text. */
public final class ToolResults {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ToolResults() {}

    public static JsonNode read(String value) {
        try {
            JsonNode node = MAPPER.readTree(value);
            if (node == null || !node.isObject() || !node.path("success").isBoolean()
                    || !node.path("code").isTextual() || node.path("code").asText().isBlank()
                    || !node.path("message").isTextual() || node.path("message").asText().isBlank()
                    || !node.has("data") || (!node.path("success").asBoolean() && "OK".equals(node.path("code").asText()))) {
                throw new IllegalArgumentException("invalid contract");
            }
            return node;
        } catch (Exception e) {
            throw new IllegalStateException("工具返回值不符合 success/code/message/data 契约", e);
        }
    }

    public static String json(Object value) {
        try { return MAPPER.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("工具结果序列化失败", e); }
    }

    /** Truncate display data while preserving a valid result envelope and complete model context. */
    public static String preview(String value) {
        if (value == null || value.length() <= 16000) return value;
        var envelope = read(value).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) envelope)
                .set("data", previewData(envelope.path("data"), new int[]{10000}, 0));
        ((com.fasterxml.jackson.databind.node.ObjectNode) envelope).put("displayTruncated", true);
        return json(envelope);
    }

    private static JsonNode previewData(JsonNode node, int[] remaining, int depth) {
        if (remaining[0] <= 0 || depth > 6) return MAPPER.getNodeFactory().textNode("…");
        if (node.isTextual()) {
            String text = node.asText();
            int length = Math.min(text.length(), Math.min(remaining[0], 5000));
            remaining[0] -= length;
            return MAPPER.getNodeFactory().textNode(text.substring(0, length) + (length < text.length() ? "…" : ""));
        }
        if (node.isObject()) {
            var result = MAPPER.createObjectNode();
            var fields = node.fields();
            int count = 0;
            while (fields.hasNext() && remaining[0] > 0 && count++ < 30) {
                var field = fields.next();
                result.set(field.getKey(), previewData(field.getValue(), remaining, depth + 1));
            }
            return result;
        }
        if (node.isArray()) {
            var result = MAPPER.createArrayNode();
            for (int i = 0; i < node.size() && i < 30 && remaining[0] > 0; i++)
                result.add(previewData(node.get(i), remaining, depth + 1));
            return result;
        }
        return node;
    }
}
