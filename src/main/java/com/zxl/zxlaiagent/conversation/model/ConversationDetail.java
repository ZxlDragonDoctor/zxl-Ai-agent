package com.zxl.zxlaiagent.conversation.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ConversationDetail(UUID id, String title, Instant createdAt, Instant updatedAt, long version,
                                 List<MessageRecord> messages, List<RunRecord> runs, boolean busy, String agentType,
                                 boolean readOnly) {
    public ConversationDetail(UUID id, String title, Instant createdAt, Instant updatedAt, long version,
                              List<MessageRecord> messages, List<RunRecord> runs, boolean busy, String agentType) {
        this(id, title, createdAt, updatedAt, version, messages, runs, busy, agentType, false);
    }

    public static ConversationDetail of(Conversation conversation, boolean busy) {
        return of(conversation, busy, false);
    }

    public static ConversationDetail of(Conversation conversation, boolean busy, boolean readOnly) {
        return new ConversationDetail(conversation.id(), conversation.title(), conversation.createdAt(),
                conversation.updatedAt(), conversation.version(), conversation.messages(), conversation.runs(),
                busy, conversation.agentType(), readOnly);
    }
}
