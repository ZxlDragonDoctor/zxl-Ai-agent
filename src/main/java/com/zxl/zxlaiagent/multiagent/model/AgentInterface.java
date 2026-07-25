package com.zxl.zxlaiagent.multiagent.model;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

public interface AgentInterface {
    String getAgentId();
    String getAgentType();
    AgentState getState();
    AgentCapability getCapability();
    CompletableFuture<String> executeTask(Task task);
    void handleMessage(AgentMessage message);
    void onEvent(AgentEvent event);
    void initialize(Map<String, Object> config);
    void shutdown();
}
