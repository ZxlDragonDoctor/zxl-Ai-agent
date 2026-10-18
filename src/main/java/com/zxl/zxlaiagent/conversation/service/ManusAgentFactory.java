package com.zxl.zxlaiagent.conversation.service;

import com.zxl.zxlaiagent.agent.ZxlManus;
import com.zxl.zxlaiagent.conversation.agent.AgentDefinition;
import com.zxl.zxlaiagent.conversation.agent.ConversationAgentProvider;
import com.zxl.zxlaiagent.agent.stream.DashScopeAgentStreamer;
import jakarta.annotation.Resource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ManusAgentFactory implements ConversationAgentProvider {
    @Resource private ToolCallback[] allTools;
    @Resource private ChatModel dashscopeChatModel;
    @Resource private DashScopeAgentStreamer agentStreamer;
    @Value("${agent.manus.max-steps:20}") private int maxSteps;

    @Override public AgentDefinition definition() {
        return new AgentDefinition("manus", "通用工具助手", "General tool assistant",
                "查询信息、调用工具并记录实际执行过程", "Answer questions and perform tasks with visible tool execution");
    }

    @Override public ZxlManus create() {
        ZxlManus agent = new ZxlManus(allTools, dashscopeChatModel);
        agent.setStreamClient(agentStreamer);
        agent.setMaxSteps(maxSteps);
        return agent;
    }
}
