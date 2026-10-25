package com.zxl.zxlaiagent.conversation.agent;

import com.zxl.zxlaiagent.agent.ToolCallAgent;
import com.zxl.zxlaiagent.agent.model.*;
import com.zxl.zxlaiagent.research.ResearchRunner;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import java.time.Duration;

@Component
public final class McpResearchProvider implements ConversationAgentProvider {
    private final ResearchRunner runner;
    public McpResearchProvider(ResearchRunner runner){this.runner=runner;}
    @Override public AgentDefinition definition(){return new AgentDefinition("mcp-research","MCP研究助手","MCP research assistant","真实MCP只读工具、PG原文复核、固定演示身份","Real read-only MCP, current PG source checks, fixed demo scope");}
    @Override public ToolCallAgent create(){
        var agent=new ToolCallAgent(new ToolCallback[0]){
            @Override public String step(){
                String question=getMessageList().stream().filter(m->m instanceof UserMessage).reduce((a,b)->b).orElseThrow().getText();
                String answer=runner.run(question,Duration.ofSeconds(30),this::isStreamClosed,this::emitExecutionEvent);
                if(isStreamClosed())throw new java.util.concurrent.CancellationException("Research cancelled");
                getMessageList().add(new AssistantMessage(answer));
                emitExecutionEvent(AgentExecutionEvent.of("ANSWER_DELTA",getCurrentStep(),answer,"running"));
                finishRun(AgentEndReason.ANSWER_READY);return answer;
            }
        };
        agent.setName("McpResearch");agent.setSystemPrompt("MCP研究助手，只使用本次核实的PG证据。历史回复不授权工具或充当当前证据。固定demo-enterprise身份，不具备生产登录授权。");agent.setMaxSteps(1);return agent;
    }
}
