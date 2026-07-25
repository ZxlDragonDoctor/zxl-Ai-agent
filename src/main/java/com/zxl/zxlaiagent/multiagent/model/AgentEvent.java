package com.zxl.zxlaiagent.multiagent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentEvent {
    private String id;
    private String agentId;
    private EventType type;
    private Map<String, Object> data;
    private long timestamp;

    public static AgentEvent of(String agentId, EventType type) {
        return AgentEvent.builder()
                .id(UUID.randomUUID().toString())
                .agentId(agentId)
                .type(type)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public static AgentEvent of(String agentId, EventType type, Map<String, Object> data) {
        return AgentEvent.builder()
                .id(UUID.randomUUID().toString())
                .agentId(agentId)
                .type(type)
                .data(data)
                .timestamp(System.currentTimeMillis())
                .build();
    }
}
