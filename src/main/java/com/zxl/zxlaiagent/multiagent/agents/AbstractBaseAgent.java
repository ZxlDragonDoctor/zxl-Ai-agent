package com.zxl.zxlaiagent.multiagent.agents;

import com.zxl.zxlaiagent.multiagent.bus.AgentMessageBus;
import com.zxl.zxlaiagent.multiagent.model.*;
import jakarta.annotation.Resource;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import com.zxl.zxlaiagent.multiagent.runtime.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

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
    private volatile ExecutionRuntime runtime;
    private static final ExecutorService STANDALONE = new ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), r -> { Thread t = new Thread(r, "agent-standalone"); t.setDaemon(true); return t; }, new ThreadPoolExecutor.AbortPolicy());
    public void attachRuntime(ExecutionRuntime runtime) { this.runtime = runtime; }
    protected <T> CompletableFuture<T> async(Task task, Supplier<T> action) {
        return runtime == null ? CompletableFuture.supplyAsync(action, STANDALONE) : runtime.async(task, action);
    }
    /** 新增工具/副作用时，必须在动作开始前调用该入口。控制对象不进入模型输入。 */
    protected void checkExecution(Task task) { if (runtime != null) runtime.check(task); }
    protected java.time.Duration remainingExecution(Task task,java.time.Duration cap) {
        var control=runtime==null?null:runtime.control(task);
        return control==null?cap:java.time.Duration.ofMillis(control.waitMillis(cap));
    }
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
                publishSafely(AgentEvent.of(agentId, eventType));
            }
        }
        return changed;
    }

    public abstract CompletableFuture<String> processTask(Task task);

    @Override
    public CompletableFuture<String> executeTask(Task task) {
        try { checkExecution(task); } catch (Exception e) { return CompletableFuture.failedFuture(e); }
        if (!compareAndSetState(AgentState.IDLE, AgentState.BUSY))
            return CompletableFuture.failedFuture(new WorkflowException("AGENT_BUSY", "agents." + agentId, "Agent 当前不可领取任务：" + state.get()));
        try {
            if (!TaskLifecycle.start(task, agentId).applied()) {
                compareAndSetState(AgentState.BUSY, AgentState.IDLE);
                return CompletableFuture.failedFuture(new WorkflowException("TASK_ALREADY_STARTED", "tasks." + task.getId(), "任务已领取或已结束"));
            }
        } catch (Throwable error) {
            compareAndSetState(AgentState.BUSY, AgentState.IDLE);
            return CompletableFuture.failedFuture(error);
        }
        CompletableFuture<String> result = new CompletableFuture<>();
        var once = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.function.BiConsumer<String, Throwable> finish = (output, error) -> {
            if (!once.compareAndSet(false, true)) return;
            TaskSnapshot snapshot;
            try {
                ExecutionControl control = runtime == null ? null : runtime.control(task);
                WorkflowError stop = control == null ? null : control.stopReason();
                TaskLifecycle.Transition transition;
                if (stop != null) transition = stop.code().equals("RUN_CANCELLED") ? TaskLifecycle.cancel(task, stop.forTask(task.getId())) : TaskLifecycle.fail(task, stop);
                else {
                    Supplier<TaskLifecycle.Transition> decide = () -> error == null ? TaskLifecycle.complete(task, output) : TaskLifecycle.fail(task, WorkflowError.from(error));
                    try { transition = control == null ? decide.get() : control.admit(decide); }
                    catch (WorkflowException stopped) { transition = stopped.getCode().equals("RUN_CANCELLED") ? TaskLifecycle.cancel(task, stopped.getError().forTask(task.getId())) : TaskLifecycle.fail(task, stopped.getError()); }
                }
                snapshot = transition.snapshot();
                if (transition.applied()) publishSafely(AgentEvent.of(agentId,
                        snapshot.status() == TaskStatus.COMPLETED ? EventType.TASK_COMPLETED : snapshot.status() == TaskStatus.CANCELLED ? EventType.TASK_CANCELLED : EventType.TASK_FAILED,
                        Map.of("taskId", task.getId(), "execution", snapshot)));
                else log.debug("Ignored late completion for task {} in {}", task.getId(), snapshot.status());
            } catch (Throwable finishError) {
                TaskLifecycle.fail(task, WorkflowError.from(finishError));
                snapshot = task.snapshot();
            } finally {
                compareAndSetState(AgentState.BUSY, AgentState.IDLE);
            }
            if (snapshot.status() == TaskStatus.COMPLETED) result.complete(snapshot.result());
            else result.completeExceptionally(new WorkflowException(snapshot.failure() == null
                    ? new WorkflowError("INVALID_TASK_STATE", "tasks." + task.getId(), "任务无法完成") : snapshot.failure()));
        };
        try {
            publishSafely(AgentEvent.of(agentId, EventType.TASK_STARTED, Map.of("taskId", task.getId(), "description", task.getDescription())));
            CompletableFuture<String> work = processTask(task);
            if (work == null) finish.accept(null, new WorkflowException("NULL_TASK_FUTURE", "tasks." + task.getId(), "Agent 返回了 null Future"));
            else work.whenComplete(finish);
        } catch (Throwable error) { finish.accept(null, error); }
        // Cancelling the returned waiting future does not detach ownership cleanup from the underlying work.
        return result;
    }

    protected void publishSafely(AgentEvent event) {
        try { if (messageBus != null) messageBus.publishEvent(event); }
        catch (Exception e) { log.warn("Agent event delivery failed for {}: {}", agentId, event.getType()); }
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

    public void attachMessageBus(AgentMessageBus bus) {
        this.messageBus = java.util.Objects.requireNonNull(bus);
    }

    protected String callLlm(Task task, String systemPrompt, String userMessage) {
        return controlledModelCall(task, () -> callLlm(systemPrompt,userMessage));
    }
    protected <T>T controlledModelCall(Task task,Supplier<T> action) {
        if (runtime != null) return runtime.modelCall(task, action);
        if (!TaskLifecycle.recordModelCall(task))
            throw new WorkflowException("TASK_ALREADY_TERMINAL", "tasks." + task.getId(), "任务已经结束，不能继续调用模型");
        return action.get();
    }

    protected String callLlm(String systemPrompt, String userMessage) {
        return chatClient.prompt()
                .system(systemPrompt)
                .user(userMessage)
                .call()
                .content();
    }
}
