package com.zxl.zxlaiagent.agent.model;

import org.springframework.ai.chat.messages.Message;
import java.util.List;

/** 工作线程结束时的一次快照，持久化监听器与 SSE 监听器互不覆盖。 */
public record AgentRunSnapshot(List<Message> messages, AgentEndReason endReason, String error) {
    public AgentRunSnapshot { messages = List.copyOf(messages); }
}
