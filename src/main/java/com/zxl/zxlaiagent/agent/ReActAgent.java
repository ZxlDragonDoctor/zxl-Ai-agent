package com.zxl.zxlaiagent.agent;

import com.zxl.zxlaiagent.agent.model.AgentState;
import com.zxl.zxlaiagent.agent.model.AgentEndReason;
import org.springframework.ai.chat.messages.AssistantMessage;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.util.concurrent.CancellationException;

/**
 * ReAct (Reasoning and Acting) 模式的代理抽象类  
 * 实现了思考-行动的循环模式  
 */  
@EqualsAndHashCode(callSuper = true)
@Data
public abstract class ReActAgent extends BaseAgent {  
  
    /**  
     * 处理当前状态并决定下一步行动  
     *  
     * @return 是否需要执行行动，true表示需要执行，false表示不需要执行  
     */  
    public abstract boolean think();  
  
    /**  
     * 执行决定的行动  
     *  
     * @return 行动执行结果  
     */  
    public abstract String act();  
  
    /**  
     * 执行单个步骤：思考和行动  
     *  
     * @return 步骤执行结果  
     */  
    @Override  
    public String step() {  
        boolean thinking = true;
        try {
            boolean shouldAct = think();  
            if (!shouldAct) {
                finishRun(AgentEndReason.ANSWER_READY);
                for (int i = getMessageList().size() - 1; i >= 0; i--) {
                    if (getMessageList().get(i) instanceof AssistantMessage message && message.getText() != null) {
                        return message.getText();
                    }
                }
                return "执行完成";
            }  
            thinking = false;
            return act();
        } catch (CancellationException e) {
            throw e;
        } catch (Exception e) {
            failRun(thinking ? AgentEndReason.MODEL_ERROR : AgentEndReason.TOOL_ERROR);
            if (e instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("步骤执行失败", e);
        }  
    }  
}
