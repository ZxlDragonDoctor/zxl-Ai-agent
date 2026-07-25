package com.zxl.zxlaiagent.multiagent.model;

import lombok.Builder;
import lombok.Value;

import java.util.Set;

@Value
@Builder
public class AgentCapability {
    String agentType;
    String description;
    Set<String> tools;
    Set<String> domains;
    int maxConcurrency;
    boolean canDelegate;
}
