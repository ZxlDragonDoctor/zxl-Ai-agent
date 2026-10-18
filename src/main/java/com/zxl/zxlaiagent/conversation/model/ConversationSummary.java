package com.zxl.zxlaiagent.conversation.model;

import java.time.Instant;
import java.util.UUID;

public record ConversationSummary(UUID id, String title, Instant updatedAt, int messageCount, boolean busy) {}
