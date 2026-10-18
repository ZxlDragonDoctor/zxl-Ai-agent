package com.zxl.zxlaiagent.conversation.service;

import com.zxl.zxlaiagent.conversation.model.MessageRecord;
import com.zxl.zxlaiagent.conversation.model.ToolCallRecord;
import org.springframework.ai.chat.messages.*;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;

@Component
public class ConversationMessageMapper {
    public List<MessageRecord> capture(List<Message> messages, UUID runId) {
        List<MessageRecord> records = new ArrayList<>();
        Instant now = Instant.now();
        for (Message message : messages) {
            if (message instanceof UserMessage user) records.add(MessageRecord.user(runId, user.getText(), now));
            else if (message instanceof AssistantMessage assistant) {
                records.add(new MessageRecord(UUID.randomUUID(), runId, "assistant", assistant.getText(),
                        assistant.getToolCalls().stream().map(c -> new ToolCallRecord(c.id(), c.name(), c.arguments())).toList(),
                        null, null, now));
            } else if (message instanceof ToolResponseMessage tool) {
                for (var response : tool.getResponses()) {
                    records.add(new MessageRecord(UUID.randomUUID(), runId, "tool", response.responseData(),
                            List.of(), response.id(), response.name(), now));
                }
            }
        }
        return records;
    }

    public List<Message> restore(List<MessageRecord> records) {
        List<Message> messages = new ArrayList<>();
        List<ToolResponseMessage.ToolResponse> results = new ArrayList<>();
        for (MessageRecord record : records) {
            if ("tool".equals(record.role())) {
                results.add(new ToolResponseMessage.ToolResponse(record.toolCallId(), record.toolName(), record.content()));
                continue;
            }
            if (!results.isEmpty()) { messages.add(new ToolResponseMessage(List.copyOf(results))); results.clear(); }
            if ("user".equals(record.role())) messages.add(new UserMessage(record.content()));
            else messages.add(new AssistantMessage(record.content(), Map.of(), record.toolCalls().stream()
                    .map(call -> new AssistantMessage.ToolCall(call.id(), "function", call.name(), call.arguments())).toList()));
        }
        if (!results.isEmpty()) messages.add(new ToolResponseMessage(List.copyOf(results)));
        return messages;
    }

    /** 一整个 Run 作为窗口单位；不完整或 ID/名称错配的工具组不交给模型。 */
    public boolean isCompleteGroup(List<MessageRecord> records) {
        if (records.isEmpty() || !"user".equals(records.get(0).role())) return false;
        Map<String, String> pending = new HashMap<>();
        Set<String> seen = new HashSet<>();
        boolean sawAssistant = false;
        for (int i = 1; i < records.size(); i++) {
            MessageRecord record = records.get(i);
            if ("assistant".equals(record.role())) {
                if (!pending.isEmpty()) return false;
                sawAssistant = true;
                for (ToolCallRecord call : record.toolCalls()) {
                    if (!seen.add(call.id())) return false;
                    pending.put(call.id(), call.name());
                }
            } else if ("tool".equals(record.role())) {
                if (!Objects.equals(pending.remove(record.toolCallId()), record.toolName())) return false;
            } else return false;
        }
        return sawAssistant && pending.isEmpty();
    }
}
