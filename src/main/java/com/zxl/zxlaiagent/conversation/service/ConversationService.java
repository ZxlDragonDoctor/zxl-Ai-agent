package com.zxl.zxlaiagent.conversation.service;

import com.zxl.zxlaiagent.agent.model.AgentEndReason;
import com.zxl.zxlaiagent.conversation.ConversationException;
import com.zxl.zxlaiagent.conversation.model.*;
import com.zxl.zxlaiagent.conversation.repository.ConversationRepository;
import com.zxl.zxlaiagent.conversation.agent.ConversationAgentRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class ConversationService {
    private final ConversationRepository repository;
    private final ConversationRunGuard guard;
    private final ConversationAgentRegistry agents;

    public ConversationService(ConversationRepository repository, ConversationRunGuard guard, ConversationAgentRegistry agents) {
        this.repository = repository;
        this.guard = guard;
        this.agents = agents;
    }

    public Conversation create(String title) {
        return create(title, "manus");
    }

    public Conversation create(String title, String agentType) {
        String type = agentType == null ? "manus" : agentType;
        agents.require(type);
        String normalized = title == null || title.isBlank() ? "新会话" : title.strip();
        if (normalized.length() > 100) throw badRequest("会话标题不能超过 100 个字符");
        return repository.create(normalized, type);
    }

    public List<ConversationSummary> list() {
        List<ConversationSummary> summaries = new ArrayList<>();
        for (ConversationSummary summary : repository.list()) {
            try {
                Conversation conversation = get(summary.id());
                summaries.add(conversation.summary(guard.isBusy(conversation.id())));
            } catch (ConversationException e) {
                if (e.getStatus() != HttpStatus.NOT_FOUND) throw e;
            }
        }
        summaries.sort(Comparator.comparing(ConversationSummary::updatedAt).reversed());
        return summaries;
    }

    public Conversation get(UUID id) {
        Conversation conversation = require(id);
        if (conversation.runs().stream().noneMatch(run -> "running".equals(run.status()))) return conversation;
        // 没有在进程内持有执行权的 running 是重启遗留，不自动重放工具。
        var lease = guard.tryAcquire(id);
        if (lease.isEmpty()) return conversation;
        try (var ignored = lease.get()) {
            conversation = require(id);
            if (conversation.runs().stream().anyMatch(run -> "running".equals(run.status()))) {
                var recovered = conversation.runs().stream().map(run -> "running".equals(run.status())
                        ? run.finish(AgentEndReason.OUTPUT_INTERRUPTED, run.answer(),
                            "服务重启或执行中断，本次执行未自动重试；请核对工具实际副作用", run.events()) : run).toList();
                repository.save(conversation.update(conversation.title(), conversation.messages(), recovered));
                conversation = require(id);
            }
            return conversation;
        }
    }

    public ConversationDetail detail(UUID id) {
        return ConversationDetail.of(get(id), guard.isBusy(id));
    }

    public void delete(UUID id) {
        try (var ignored = guard.acquire(id)) { repository.delete(id); }
    }

    public Conversation require(UUID id) {
        return repository.find(id).orElseThrow(() -> new ConversationException(
                "CONVERSATION_NOT_FOUND", "会话不存在", HttpStatus.NOT_FOUND));
    }

    public static UUID parseId(String value) {
        try {
            if (value == null || !value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
                throw new IllegalArgumentException();
            }
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) { throw badRequest("conversationId 必须是有效 UUID"); }
    }

    public static ConversationException badRequest(String message) {
        return new ConversationException("INVALID_CONVERSATION_REQUEST", message, HttpStatus.BAD_REQUEST);
    }
}
