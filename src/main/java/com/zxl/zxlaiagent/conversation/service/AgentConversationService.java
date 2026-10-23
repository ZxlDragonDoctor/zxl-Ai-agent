package com.zxl.zxlaiagent.conversation.service;

import com.zxl.zxlaiagent.agent.model.*;
import com.zxl.zxlaiagent.agent.ToolCallAgent;
import com.zxl.zxlaiagent.conversation.context.ContextAssembler;
import com.zxl.zxlaiagent.conversation.model.*;
import com.zxl.zxlaiagent.conversation.repository.ConversationRepository;
import com.zxl.zxlaiagent.conversation.agent.ConversationAgentRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.time.Instant;
import java.util.*;

/** 会话协调器负责仓储和执行权；Agent 仍只负责一次执行。 */
@Slf4j
@Service
public class AgentConversationService {
    private final ConversationService conversations;
    private final ConversationRepository repository;
    private final ConversationRunGuard guard;
    private final ConversationContextWindow window;
    private final ConversationMessageMapper mapper;
    private final ConversationAgentRegistry agents;

    public AgentConversationService(ConversationService conversations, ConversationRepository repository,
            ConversationRunGuard guard, ConversationContextWindow window,
            ConversationMessageMapper mapper, ConversationAgentRegistry agents) {
        this.conversations = conversations;
        this.repository = repository;
        this.guard = guard;
        this.window = window;
        this.mapper = mapper;
        this.agents = agents;
    }

    public SseEmitter chat(String message, String conversationId) {
        if (message == null || message.isBlank() || message.length() > 12000) {
            throw ConversationService.badRequest("消息不能为空且不能超过 12000 个字符");
        }
        String prompt = message.strip();
        ToolCallAgent preparedAgent = null;
        ContextAssembler.Plan preparedContext = null;
        if (conversationId == null) {
            // Validate against the actual provider before creating any stored conversation.
            // The ephemeral metadata is never saved and contains no prior messages.
            preparedAgent = agents.require("manus").create();
            Instant at = Instant.now();
            var empty = new Conversation(UUID.randomUUID(), "新会话", at, at, 0, List.of(), List.of(), "manus");
            preparedContext = window.prepare(empty, preparedAgent, prompt);
        }
        UUID id = conversationId == null ? conversations.create(null).id() : ConversationService.parseId(conversationId);
        conversations.get(id); // 懒恢复重启遗留的任务；不把它们当作仍在运行。
        var lease = guard.acquire(id);
        UUID runId = UUID.randomUUID();
        boolean started = false;
        try {
            Conversation previous = conversations.require(id);
            var agent = preparedAgent == null ? agents.require(previous.agentType()).create() : preparedAgent;
            var context = preparedContext == null ? window.prepare(previous, agent, prompt) : preparedContext;
            var history = context.history();
            agent.restoreMessages(history);
            agent.setConversationId(id.toString());
            agent.setRunId(runId.toString());
            Instant now = Instant.now();
            RunRecord run = RunRecord.started(runId, now);
            MessageRecord user = MessageRecord.user(runId, prompt, now);
            List<MessageRecord> messages = new ArrayList<>(previous.messages());
            messages.add(user);
            List<RunRecord> runs = new ArrayList<>(previous.runs());
            runs.add(run);
            String title = previous.title();
            if (previous.runs().isEmpty() && ("新会话".equals(title) || "New conversation".equals(title))) {
                title = prompt.lines().findFirst().orElse(prompt);
                title = title.substring(0, Math.min(40, title.length()));
            }
            repository.save(previous.update(title, messages, runs));
            started = true;
            RunCapture capture = new RunCapture();
            agent.setEventObserver(capture::accept);
            agent.setCompletionListener(snapshot -> {
                try { finish(id, run, user, history.size(), snapshot, capture); }
                finally { lease.close(); }
            });
            return agent.runStream(prompt);
        } catch (RuntimeException error) {
            if (started) {
                try {
                    Conversation current = conversations.require(id);
                    var failedRuns = current.runs().stream().map(run -> run.runId().equals(runId)
                            && "running".equals(run.status()) ? run.finish(AgentEndReason.EXECUTION_ERROR,
                            "", "执行未能启动，请检查服务端日志", List.of()) : run).toList();
                    repository.save(current.update(current.title(), current.messages(), failedRuns));
                } catch (RuntimeException storageError) {
                    log.error("Unable to record failed run startup: {}", runId, storageError);
                }
            }
            lease.close();
            throw error;
        }
    }

    /** Read-only run-start projection; the summary is generated from stored messages, never accepted from the request. */
    public com.zxl.zxlaiagent.conversation.context.ContextAssembler.Plan previewContext(String conversationId, String prompt) {
        if (prompt == null || prompt.isBlank() || prompt.length() > 12000)
            throw ConversationService.badRequest("消息不能为空且不能超过 12000 个字符");
        var conversation = conversations.require(ConversationService.parseId(conversationId));
        var agent = agents.require(conversation.agentType()).create();
        return window.prepare(conversation, agent, prompt.strip());
    }

    private void finish(UUID id, RunRecord run, MessageRecord user, int historySize,
                        AgentRunSnapshot snapshot, RunCapture capture) {
        Conversation current = conversations.require(id);
        RunRecord existing = current.runs().stream().filter(item -> item.runId().equals(run.runId()))
                .findFirst().orElseThrow(() -> new IllegalStateException("执行记录丢失"));
        if (!"running".equals(existing.status())) return; // 完成回调按 runId 去重。
        List<MessageRecord> newMessages = mapper.capture(snapshot.messages().subList(historySize, snapshot.messages().size()), run.runId());
        if (newMessages.isEmpty()) newMessages.add(user);
        else newMessages.set(0, user); // startRun 已追加一次用户消息；保留其原 messageId。
        List<MessageRecord> messages = new ArrayList<>(current.messages().stream()
                .filter(item -> !item.runId().equals(run.runId())).toList());
        messages.addAll(newMessages);
        RunRecord finished = run.finish(snapshot.endReason(), capture.answer.toString(), snapshot.error(), capture.events);
        var runs = current.runs().stream().map(item -> item.runId().equals(run.runId()) ? finished : item).toList();
        repository.save(current.update(current.title(), messages, runs));
    }

    private static final class RunCapture {
        private final List<AgentExecutionEvent> events = new ArrayList<>();
        private final StringBuilder answer = new StringBuilder();
        private int answerStep;
        void accept(AgentExecutionEvent event) {
            if ("ANSWER_DELTA".equals(event.type())) {
                if (answerStep != event.step() && !answer.isEmpty()) answer.append("\n\n");
                answerStep = event.step();
                if (event.content() != null) answer.append(event.content());
            } else if (!"THINKING_DELTA".equals(event.type()) && events.size() < 2048) {
                // 保存工具与步骤证据；不保存 provider 的思考增量。
                events.add(event);
            }
        }
    }
}
