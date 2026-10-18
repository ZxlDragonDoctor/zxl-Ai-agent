package com.zxl.zxlaiagent.conversation.service;

import com.zxl.zxlaiagent.conversation.model.Conversation;
import com.zxl.zxlaiagent.conversation.model.MessageRecord;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class ConversationContextWindow {
    private final ConversationMessageMapper mapper;
    private final int maxRuns;

    public ConversationContextWindow(ConversationMessageMapper mapper,
            @Value("${agent.conversation.context-runs:8}") int maxRuns) {
        if (maxRuns < 1 || maxRuns > 50) throw new IllegalArgumentException("context-runs 必须在 1 到 50 之间");
        this.mapper = mapper;
        this.maxRuns = maxRuns;
    }

    public List<Message> restore(Conversation conversation) {
        Map<UUID, List<MessageRecord>> grouped = new HashMap<>();
        for (MessageRecord record : conversation.messages()) {
            grouped.computeIfAbsent(record.runId(), ignored -> new ArrayList<>()).add(record);
        }
        List<List<MessageRecord>> complete = conversation.runs().stream()
                .filter(run -> !"running".equals(run.status()))
                .map(run -> grouped.getOrDefault(run.runId(), List.of()))
                .filter(mapper::isCompleteGroup).toList();
        List<MessageRecord> selected = complete.subList(Math.max(0, complete.size() - maxRuns), complete.size())
                .stream().flatMap(List::stream).toList();
        return mapper.restore(selected);
    }
}
