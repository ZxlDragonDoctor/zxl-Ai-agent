package com.zxl.zxlaiagent.conversation.agent;

import com.zxl.zxlaiagent.agent.ToolCallAgent;
import com.zxl.zxlaiagent.agent.stream.DashScopeAgentStreamer;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

/** 角色型示例：同样使用会话引擎，不向它授予文件或命令工具。 */
@Component
public class LoveAssistantProvider implements ConversationAgentProvider {
    private final DashScopeAgentStreamer streamer;
    public LoveAssistantProvider(DashScopeAgentStreamer streamer) { this.streamer = streamer; }

    @Override public AgentDefinition definition() {
        return new AgentDefinition("love-assistant", "恋爱助手", "Relationship assistant",
                "用于测试角色提示词和连续对话的示例助手", "A sample agent for role prompts and conversation memory");
    }

    @Override public ToolCallAgent create() {
        ToolCallAgent agent = new ToolCallAgent(new ToolCallback[0]);
        agent.setName("LoveAssistant");
        agent.setSystemPrompt("""
                你是帮助用户梳理亲密关系与沟通问题的恋爱助手。
                根据用户已提供的背景，以有同理心、尊重双方意愿的方式给出具体建议。
                信息不足时提出一个有用的澄清问题，避免反复自我介绍。
                不虚构用户背景，不声称具有医疗诊断资质；使用用户当前使用的语言。
                """);
        agent.setMaxSteps(1);
        agent.setStreamClient(streamer);
        return agent;
    }

    @Override public boolean supportsAuthenticatedExecution() { return true; }

    /** Execution service binds the actor's conversation/run boundary before this agent starts. */
    @Override public ToolCallAgent create(com.zxl.zxlaiagent.security.ActorRef actor, AgentRunOptions options) {
        if (actor == null) throw new com.zxl.zxlaiagent.security.SecurityFailure("UNAUTHENTICATED", 401);
        if (options != null && !options.isEmpty()) throw new IllegalArgumentException("AGENT_DOES_NOT_ACCEPT_SKILL_SELECTION");
        return create();
    }
}
