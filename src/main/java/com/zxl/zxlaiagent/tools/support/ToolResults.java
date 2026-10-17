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
}
