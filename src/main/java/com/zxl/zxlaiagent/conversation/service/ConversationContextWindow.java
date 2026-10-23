package com.zxl.zxlaiagent.conversation.service;

import com.zxl.zxlaiagent.conversation.model.Conversation;
import com.zxl.zxlaiagent.conversation.model.MessageRecord;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.*;
import com.zxl.zxlaiagent.agent.ToolCallAgent;
import com.zxl.zxlaiagent.conversation.context.ContextAssembler;

@Component
public class ConversationContextWindow {
    private final ConversationMessageMapper mapper;
    private final int maxRuns;
    private final ContextAssembler assembler;

    public ConversationContextWindow(ConversationMessageMapper mapper,
            @Value("${agent.conversation.context-runs:8}") int maxRuns) {
        if (maxRuns < 1 || maxRuns > 50) throw new IllegalArgumentException("context-runs 必须在 1 到 50 之间");
        this.mapper = mapper;
        this.maxRuns = maxRuns;
        this.assembler = new ContextAssembler(mapper);
    }

    /** Entry budget includes actual provider prompts and tool definitions; later step growth is separately reserved, not capped here. */
    public ContextAssembler.Plan prepare(Conversation conversation, ToolCallAgent agent, String prompt) {
        StringBuilder schemas = new StringBuilder();
        for (var tool : agent.getAvailableTools()) {
            var definition = tool.getToolDefinition();
            schemas.append(definition.name()).append('\n').append(definition.description()).append('\n').append(definition.inputSchema()).append('\n');
        }
        try {
            return assembler.assemble(conversation, agent.getSystemPrompt(), prompt, agent.getNextStepPrompt(),
                    schemas.toString(), ContextAssembler.Limits.defaults(maxRuns));
        } catch (ContextAssembler.BudgetExceeded e) { throw ConversationService.badRequest(e.getMessage()); }
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
