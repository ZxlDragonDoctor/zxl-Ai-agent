package com.zxl.zxlaiagent.conversation.model;

import java.time.Instant;
import java.util.UUID;

public record ConversationSummary(UUID id, String title, Instant updatedAt, int messageCount, boolean busy,
                                  String agentType, boolean readOnly) {
    public ConversationSummary(UUID id, String title, Instant updatedAt, int messageCount, boolean busy, String agentType) {
        this(id, title, updatedAt, messageCount, busy, agentType, false);
    }
}
