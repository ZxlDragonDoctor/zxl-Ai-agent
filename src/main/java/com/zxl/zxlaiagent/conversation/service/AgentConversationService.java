package com.zxl.zxlaiagent.conversation.service;

import com.zxl.zxlaiagent.agent.model.*;
import com.zxl.zxlaiagent.agent.ToolCallAgent;
import com.zxl.zxlaiagent.conversation.context.ContextAssembler;
import com.zxl.zxlaiagent.conversation.model.*;
import com.zxl.zxlaiagent.conversation.repository.ConversationRepository;
import com.zxl.zxlaiagent.conversation.agent.ConversationAgentRegistry;
import com.zxl.zxlaiagent.conversation.agent.AgentRunOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import com.zxl.zxlaiagent.security.*;
import com.zxl.zxlaiagent.security.execution.ActorBoundary;
import com.zxl.zxlaiagent.agent.stream.PreparedAgentStream;
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
    private AuthorizationService authorization;

    @Autowired(required = false)
    public void setAuthorizationService(AuthorizationService authorization) { this.authorization = authorization; }

    private AuthorizationService authorization() {
        if (authorization == null) throw new SecurityFailure("IDENTITY_SERVICE_UNAVAILABLE", 503);
        return authorization;
    }

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
        return chat(message, conversationId, AgentRunOptions.NONE);
    }

    public SseEmitter chat(String message, String conversationId, AgentRunOptions options) {
        return chatInternal(null, message, conversationId, options);
    }

    public SseEmitter chat(ActorRef actor, String message, String conversationId, AgentRunOptions options) {
        throw new IllegalStateException("正式对话使用 prepareChat，并由 MVC prepared-stream hook 激活");
    }

    public PreparedAgentStream prepareChat(ActorRef actor, String message, String conversationId, AgentRunOptions options) {
        if (actor == null) throw new SecurityFailure("UNAUTHENTICATED", 401);
        authorization().decide(actor, "agent.execute", null, Phase.ENTRY, null).requireAllowed();
        return (PreparedAgentStream) chatInternal(actor, message, conversationId, options);
    }

    private SseEmitter chatInternal(ActorRef actor, String message, String conversationId, AgentRunOptions options) {
        options = options == null ? AgentRunOptions.NONE : options;
        if (message == null || message.isBlank() || message.length() > 12000) {
            throw ConversationService.badRequest("消息不能为空且不能超过 12000 个字符");
        }
        String prompt = message.strip();
        ToolCallAgent preparedAgent = null;
        ContextAssembler.Plan preparedContext = null;
        if (conversationId == null) {
            // Validate against the actual provider before creating any stored conversation.
            // The ephemeral metadata is never saved and contains no prior messages.
            preparedAgent = createAgent(actor, agents.require("manus"), options);
            Instant at = Instant.now();
            var empty = new Conversation(UUID.randomUUID(), "新会话", at, at, 0, List.of(), List.of(), "manus");
            preparedContext = window.prepare(empty, preparedAgent, prompt);
        }
        UUID id = conversationId == null ? (actor == null ? conversations.create(null) : conversations.create(actor, null, "manus")).id() : ConversationService.parseId(conversationId);
        if (actor == null) conversations.get(id);
        else conversations.get(actor, id);
        var lease = guard.acquire(id);
        UUID runId = UUID.randomUUID();
        boolean started = false;
        try {
            Conversation previous = actor == null ? conversations.require(id) : conversations.require(actor, id);
            var provider = agents.require(previous.agentType());
            var agent = preparedAgent == null ? createAgent(actor, provider, options) : preparedAgent;
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
            Conversation starting = previous.update(title, messages, runs);
            if (actor == null) repository.save(starting);
            else {
                authorization().registerResource(actor, new ResourceRef("run", runId.toString()));
                authorization().gate(actor, "conversation.writeOwn", new ResourceRef("conversation", id.toString()),
                        Phase.PERSIST, runId.toString(), () -> { repository.save(starting); return null; });
                agent.bindOperationBoundary(new ActorBoundary(authorization(), actor, List.of(
                        new AuthorizationService.Permission("agent.execute", null),
                        new AuthorizationService.Permission("conversation.readOwn", new ResourceRef("conversation", id.toString())),
                        new AuthorizationService.Permission("run.readOwn", new ResourceRef("run", runId.toString()))), runId.toString()));
            }
            started = true;
            RunCapture capture = new RunCapture();
            agent.setEventObserver(capture::accept);
            agent.setCompletionListener(snapshot -> {
                try {
                    if (actor == null) finish(id, run, user, history.size(), snapshot, capture);
                    else authorization().gate(actor, "conversation.writeOwn", new ResourceRef("conversation", id.toString()),
                            Phase.RESULT_PERSIST, runId.toString(), () -> { finish(id, run, user, history.size(), snapshot, capture); return null; });
                }
                finally { lease.close(); }
            });
            return actor == null ? agent.runStream(prompt) : agent.prepareStream(prompt);
        } catch (RuntimeException error) {
            if (started) {
                try {
                    Conversation current = actor == null ? conversations.require(id) : conversations.require(actor, id);
                    var failedRuns = current.runs().stream().map(run -> run.runId().equals(runId)
                            && "running".equals(run.status()) ? run.finish(AgentEndReason.EXECUTION_ERROR,
                            "", "执行未能启动，请检查服务端日志", List.of()) : run).toList();
                    Conversation failed = current.update(current.title(), current.messages(), failedRuns);
                    if (actor == null) repository.save(failed);
                    else authorization().gate(actor, "conversation.writeOwn", new ResourceRef("conversation", id.toString()),
                            Phase.RESULT_PERSIST, runId.toString(), () -> { repository.save(failed); return null; });
                } catch (RuntimeException storageError) {
                    log.error("Unable to record failed run startup: {}", runId, storageError);
                }
            }
            lease.close();
            throw error;
        }
    }

    private ToolCallAgent createAgent(ActorRef actor, com.zxl.zxlaiagent.conversation.agent.ConversationAgentProvider provider,
                                      AgentRunOptions options) {
        return actor == null ? (options.isEmpty() ? provider.create() : provider.create(options)) : provider.create(actor, options);
    }

    public ContextAssembler.Plan previewContext(ActorRef actor, String conversationId, String prompt) {
        if (actor == null) throw new SecurityFailure("UNAUTHENTICATED", 401);
        if (prompt == null || prompt.isBlank() || prompt.length() > 12000)
            throw ConversationService.badRequest("消息不能为空且不能超过 12000 个字符");
        var conversation = conversations.require(actor, ConversationService.parseId(conversationId));
        var agent = createAgent(actor, agents.require(conversation.agentType()), AgentRunOptions.NONE);
        return window.prepare(conversation, agent, prompt.strip());
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
