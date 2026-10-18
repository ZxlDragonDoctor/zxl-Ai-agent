package com.zxl.zxlaiagent.conversation.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ConversationDetail(UUID id, String title, Instant createdAt, Instant updatedAt, long version,
                                 List<MessageRecord> messages, List<RunRecord> runs, boolean busy, String agentType) {
    public static ConversationDetail of(Conversation conversation, boolean busy) {
        return new ConversationDetail(conversation.id(), conversation.title(), conversation.createdAt(),
                conversation.updatedAt(), conversation.version(), conversation.messages(), conversation.runs(), busy, conversation.agentType());
    }
}
