package com.zxl.zxlaiagent.multiagent.agents;

import com.zxl.zxlaiagent.multiagent.bus.AgentMessageBus;
import com.zxl.zxlaiagent.multiagent.model.*;
import jakarta.annotation.Resource;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public abstract class AbstractBaseAgent implements AgentInterface {

    @Getter
    protected String agentId;
    @Getter
    protected String agentType;
    protected final AtomicReference<AgentState> state = new AtomicReference<>(AgentState.IDLE);
    @Getter
    protected AgentCapability capability;
    @Setter
    protected ChatClient chatClient;
    protected ChatModel chatModel;
    @Resource
    protected AgentMessageBus messageBus;
    protected final Map<String, Object> config = new ConcurrentHashMap<>();

    public AbstractBaseAgent(String agentId, String agentType, ChatModel chatModel) {
        this.agentId = agentId;
        this.agentType = agentType;
        this.chatModel = chatModel;
        this.chatClient = ChatClient.builder(chatModel).build();
    }

    @Override
    public AgentState getState() {
        return state.get();
    }

    protected boolean compareAndSetState(AgentState expected, AgentState newState) {
        boolean changed = state.compareAndSet(expected, newState);
        if (changed) {
            EventType eventType = switch (newState) {
                case BUSY -> EventType.AGENT_BUSY;
                case IDLE -> EventType.AGENT_IDLE;
                case ERROR -> EventType.AGENT_ERROR;
                default -> null;
            };
            if (eventType != null) {
                messageBus.publishEvent(AgentEvent.of(agentId, eventType));
            }
        }
        return changed;
    }

    public abstract CompletableFuture<String> processTask(Task task);

    @Override
    public CompletableFuture<String> executeTask(Task task) {
        if (!compareAndSetState(AgentState.IDLE, AgentState.BUSY)) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Agent " + agentId + " is " + state.get()));
        }
        messageBus.publishEvent(AgentEvent.of(agentId, EventType.TASK_STARTED,
                Map.of("taskId", task.getId(), "description", task.getDescription())));
        task.setStatus(TaskStatus.IN_PROGRESS);
        task.setStartedAt(System.currentTimeMillis());
        return processTask(task).handle((result, error) -> {
            if (error != null) {
                task.setStatus(TaskStatus.FAILED);
                String errorMessage = error.getMessage() != null
                        ? error.getMessage()
                        : error.getClass().getSimpleName();
                task.setError(errorMessage);
                messageBus.publishEvent(AgentEvent.of(agentId, EventType.TASK_FAILED,
                        Map.of("taskId", task.getId(), "error", errorMessage)));
                compareAndSetState(AgentState.BUSY, AgentState.IDLE);
                throw new RuntimeException(error);
            }
            task.setStatus(TaskStatus.COMPLETED);
            task.setResult(result);
            task.setCompletedAt(System.currentTimeMillis());
            messageBus.publishEvent(AgentEvent.of(agentId, EventType.TASK_COMPLETED,
                    Map.of("taskId", task.getId(), "result", result)));
            state.set(AgentState.IDLE);
            return result;
        });
    }

    @Override
    public void handleMessage(AgentMessage message) {
        log.debug("Agent {} received message from {}: {}", agentId, message.getSourceAgentId(), message.getType());
        switch (message.getType()) {
            case QUERY -> handleQuery(message);
            case TASK_ASSIGN -> handleTaskAssign(message);
            case SHUTDOWN -> shutdown();
            default -> log.debug("Unhandled message type: {}", message.getType());
        }
    }

    protected void handleQuery(AgentMessage message) {
        AgentMessage response = AgentMessage.create(agentId, message.getSourceAgentId(),
                MessageType.RESPONSE, "Agent " + agentId + " is " + state.get());
        messageBus.sendMessage(response);
    }

    protected void handleTaskAssign(AgentMessage message) {
        log.info("Agent {} received task assignment: {}", agentId, message.getContent());
    }

    @Override
    public void onEvent(AgentEvent event) {
        log.debug("Agent {} received event: {}", agentId, event.getType());
    }

    @Override
    public void initialize(Map<String, Object> config) {
        this.config.putAll(config);
        log.info("Agent {} initialized with config: {}", agentId, config);
    }

    @Override
    public void shutdown() {
        state.set(AgentState.SHUTDOWN);
        messageBus.unregisterAgent(agentId);
        log.info("Agent {} shutdown", agentId);
    }

    protected String callLlm(String systemPrompt, String userMessage) {
        return chatClient.prompt()
                .system(systemPrompt)
                .user(userMessage)
                .call()
                .content();
    }
}
