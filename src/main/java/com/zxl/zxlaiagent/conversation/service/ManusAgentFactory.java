package com.zxl.zxlaiagent.conversation.service;

import com.zxl.zxlaiagent.agent.ZxlManus;
import com.zxl.zxlaiagent.agent.stream.DashScopeAgentStreamer;
import jakarta.annotation.Resource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ManusAgentFactory {
    @Resource private ToolCallback[] allTools;
    @Resource private ChatModel dashscopeChatModel;
    @Resource private DashScopeAgentStreamer agentStreamer;
    @Value("${agent.manus.max-steps:20}") private int maxSteps;

    public ZxlManus create() {
        ZxlManus agent = new ZxlManus(allTools, dashscopeChatModel);
        agent.setStreamClient(agentStreamer);
        agent.setMaxSteps(maxSteps);
        return agent;
    }
}
