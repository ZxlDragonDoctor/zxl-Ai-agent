package com.zxl.zxlaiagent.conversation.agent;

import com.zxl.zxlaiagent.agent.ToolCallAgent;

/** 新增 Agent 类型只需实现此接口并注册为 Spring Bean，复用会话与页面。 */
public interface ConversationAgentProvider {
    AgentDefinition definition();
    ToolCallAgent create();
    default ToolCallAgent create(AgentRunOptions options) {
        if (options != null && !options.isEmpty()) throw new IllegalArgumentException("AGENT_DOES_NOT_ACCEPT_SKILL_SELECTION");
        return create();
    }
}
