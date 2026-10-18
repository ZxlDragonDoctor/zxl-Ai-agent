package com.zxl.zxlaiagent.conversation.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record Conversation(UUID id, String title, Instant createdAt, Instant updatedAt, long version,
                           List<MessageRecord> messages, List<RunRecord> runs, String agentType) {
    public Conversation {
        Objects.requireNonNull(id);
        Objects.requireNonNull(createdAt);
        Objects.requireNonNull(updatedAt);
        if (title == null || title.isBlank() || version < 0) throw new IllegalArgumentException("会话元数据无效");
        messages = List.copyOf(messages);
        runs = List.copyOf(runs);
        agentType = agentType == null ? "manus" : agentType;
        var runIds = runs.stream().map(RunRecord::runId).collect(java.util.stream.Collectors.toSet());
        if (runIds.size() != runs.size() || messages.stream().anyMatch(m -> !runIds.contains(m.runId()))) {
            throw new IllegalArgumentException("会话中存在重复执行或未关联的消息");
        }
    }

    public Conversation update(String title, List<MessageRecord> messages, List<RunRecord> runs) {
        return new Conversation(id, title, createdAt, Instant.now(), version, messages, runs, agentType);
    }

    public ConversationSummary summary(boolean busy) {
        return new ConversationSummary(id, title, updatedAt, messages.size(), busy, agentType);
    }
}
