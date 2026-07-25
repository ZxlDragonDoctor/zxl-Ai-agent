package com.zxl.zxlaiagent.multiagent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentMessage {
    private String id;
    private String sourceAgentId;
    private String targetAgentId;
    private MessageType type;
    private String content;
    private Map<String, Object> metadata;
    private long timestamp;

    public static AgentMessage create(String source, String target, MessageType type, String content) {
        return AgentMessage.builder()
                .id(UUID.randomUUID().toString())
                .sourceAgentId(source)
                .targetAgentId(target)
                .type(type)
                .content(content)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public static AgentMessage broadcast(String source, MessageType type, String content) {
        return AgentMessage.builder()
                .id(UUID.randomUUID().toString())
                .sourceAgentId(source)
                .targetAgentId("*")
                .type(type)
                .content(content)
                .timestamp(System.currentTimeMillis())
                .build();
    }
}
