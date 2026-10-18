package com.zxl.zxlaiagent.conversation.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 存储格式与 Spring AI Message 独立；toolCallId 必须沿用模型原始 ID。 */
public record MessageRecord(UUID messageId, UUID runId, String role, String content,
                            List<ToolCallRecord> toolCalls, String toolCallId, String toolName,
                            Instant createdAt) {
    public MessageRecord {
        Objects.requireNonNull(messageId);
        Objects.requireNonNull(runId);
        Objects.requireNonNull(createdAt);
        if (!Set.of("user", "assistant", "tool").contains(role)) {
            throw new IllegalArgumentException("不支持的历史消息角色");
        }
        content = content == null ? "" : content;
        toolCalls = List.copyOf(toolCalls);
        if ("tool".equals(role) && (toolCallId == null || toolCallId.isBlank()
                || toolName == null || toolName.isBlank())) {
            throw new IllegalArgumentException("工具结果缺少调用关联信息");
        }
        if (!"assistant".equals(role) && !toolCalls.isEmpty()) {
            throw new IllegalArgumentException("只有助手消息可以包含工具调用");
        }
    }

    public static MessageRecord user(UUID runId, String content, Instant at) {
        return new MessageRecord(UUID.randomUUID(), runId, "user", content, List.of(), null, null, at);
    }
}
