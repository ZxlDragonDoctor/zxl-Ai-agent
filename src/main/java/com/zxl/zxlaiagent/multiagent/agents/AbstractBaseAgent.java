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
    private volatile boolean scopedExecutionRequired;
    public void requireScopedExecution() { scopedExecutionRequired=true; }
    private volatile com.zxl.zxlaiagent.agent.stream.DashScopeAgentStreamer scopedStreamer;
    public void attachScopedStreamer(com.zxl.zxlaiagent.agent.stream.DashScopeAgentStreamer streamer) { this.scopedStreamer=java.util.Objects.requireNonNull(streamer); }
    protected ExecutionRuntime.ExecutionScope executionScope(Task task) {
        return runtime==null?new ExecutionRuntime.ExecutionScope(null):runtime.executionScope(task);
    }
    private static final ExecutorService STANDALONE = new ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), r -> { Thread t = new Thread(r, "agent-standalone"); t.setDaemon(true); return t; }, new ThreadPoolExecutor.AbortPolicy());
    public void attachRuntime(ExecutionRuntime runtime) { this.runtime = runtime; }
    protected <T> CompletableFuture<T> async(Task task, Supplier<T> action) {
        return runtime == null ? CompletableFuture.supplyAsync(action, STANDALONE) : runtime.async(task, action);
    }
    /** 新增工具/副作用时，必须在动作开始前调用该入口。控制对象不进入模型输入。 */
    protected void checkExecution(Task task) {
        if(scopedExecutionRequired&&!executionScope(task).scoped())throw new com.zxl.zxlaiagent.security.SecurityFailure("SCOPED_EXECUTION_CONTEXT_REQUIRED",403);
        if (runtime != null) runtime.check(task);
    }
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
        var scope=executionScope(task);
        if(scope.scoped()&&scope.trustedContext()==null)
            return CompletableFuture.failedFuture(new com.zxl.zxlaiagent.security.SecurityFailure("SCOPED_RUN_CONTEXT_REQUIRED",403));
        if (!changeTaskState(scope,AgentState.IDLE, AgentState.BUSY))
            return CompletableFuture.failedFuture(new WorkflowException("AGENT_BUSY", "agents." + agentId, "Agent 当前不可领取任务：" + state.get()));
        try {
            if (!TaskLifecycle.start(task, agentId).applied()) {
                changeTaskState(scope,AgentState.BUSY, AgentState.IDLE);
                return CompletableFuture.failedFuture(new WorkflowException("TASK_ALREADY_STARTED", "tasks." + task.getId(), "任务已领取或已结束"));
            }
        } catch (Throwable error) {
            changeTaskState(scope,AgentState.BUSY, AgentState.IDLE);
            return CompletableFuture.failedFuture(error);
        }
        CompletableFuture<String> result = new CompletableFuture<>();
        var once = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.function.BiConsumer<String, Throwable> finish = (output, error) -> {
            if (!once.compareAndSet(false, true)) return;
            TaskSnapshot snapshot;
            try {
                Supplier<TaskSnapshot> completion=()->finishTask(task,scope,output,error);
                snapshot=scope.scoped()?scope.boundary().start(com.zxl.zxlaiagent.security.Phase.RESULT_PUBLISH,completion):completion.get();
            } catch (Throwable finishError) {
                TaskLifecycle.fail(task, WorkflowError.from(finishError));
                result.completeExceptionally(finishError);
                return;
            } finally {
                changeTaskState(scope,AgentState.BUSY, AgentState.IDLE);
            }
            if (snapshot.status() == TaskStatus.COMPLETED) result.complete(snapshot.result());
            else result.completeExceptionally(new WorkflowException(snapshot.failure() == null
                    ? new WorkflowError("INVALID_TASK_STATE", "tasks." + task.getId(), "任务无法完成") : snapshot.failure()));
        };
        try {
            publishTaskEvent(scope,AgentEvent.of(agentId, EventType.TASK_STARTED, Map.of("taskId", task.getId(), "description", task.getDescription())));
            CompletableFuture<String> work = processTask(task);
            if (work == null) finish.accept(null, new WorkflowException("NULL_TASK_FUTURE", "tasks." + task.getId(), "Agent 返回了 null Future"));
            else work.whenComplete(finish);
        } catch (Throwable error) { finish.accept(null, error); }
        // Cancelling the returned waiting future does not detach ownership cleanup from the underlying work.
        return result;
    }

    private boolean changeTaskState(ExecutionRuntime.ExecutionScope scope,AgentState expected,AgentState update){
        // A scoped task must not disclose even its task activity through the legacy observer bus.
        return scope.scoped()?state.compareAndSet(expected,update):compareAndSetState(expected,update);
    }
    private TaskSnapshot finishTask(Task task,ExecutionRuntime.ExecutionScope scope,String output,Throwable error){
        ExecutionControl control=scope.scoped()?scope.control():runtime==null?null:runtime.control(task);
        WorkflowError stop=control==null?null:control.stopReason();
        TaskLifecycle.Transition transition;
        if(stop!=null)transition=stop.code().equals("RUN_CANCELLED")?TaskLifecycle.cancel(task,stop.forTask(task.getId())):TaskLifecycle.fail(task,stop);
        else{
            Supplier<TaskLifecycle.Transition> decide=()->error==null?TaskLifecycle.complete(task,output):TaskLifecycle.fail(task,WorkflowError.from(error));
            try{transition=control==null?decide.get():control.admit(decide);}
            catch(WorkflowException stopped){transition=stopped.getCode().equals("RUN_CANCELLED")?TaskLifecycle.cancel(task,stopped.getError().forTask(task.getId())):TaskLifecycle.fail(task,stopped.getError());}
        }
        var snapshot=transition.snapshot();
        if(transition.applied())deliverTaskEvent(scope,AgentEvent.of(agentId,
                snapshot.status()==TaskStatus.COMPLETED?EventType.TASK_COMPLETED:snapshot.status()==TaskStatus.CANCELLED?EventType.TASK_CANCELLED:EventType.TASK_FAILED,
                Map.of("taskId",task.getId(),"execution",snapshot)));
        return snapshot;
    }
    /** Scoped sinks only enqueue locally; callers must never block on a remote observer in this gate. */
    protected void publishTaskEvent(ExecutionRuntime.ExecutionScope scope,AgentEvent event){
        if(!scope.scoped()){publishSafely(event);return;}
        scope.boundary().start(com.zxl.zxlaiagent.security.Phase.RESULT_PUBLISH,()->{deliverTaskEvent(scope,event);return null;});
    }
    /** Only call directly when the caller already owns this operation's authorization gate. */
    protected final void deliverTaskEvent(ExecutionRuntime.ExecutionScope scope,AgentEvent event){
        if(!scope.scoped()){publishSafely(event);return;}
        var context=java.util.Objects.requireNonNull(scope.trustedContext(),"Trusted event sink required");
        var data=new java.util.LinkedHashMap<String,Object>(event.getData()==null?Map.of():event.getData());
        data.put("executionId",context.runId());event.setData(java.util.Collections.unmodifiableMap(data));
        context.events().accept(event);
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
        var scope=executionScope(task);
        if(scopedExecutionRequired&&!scope.scoped())throw new com.zxl.zxlaiagent.security.SecurityFailure("SCOPED_EXECUTION_CONTEXT_REQUIRED",403);
        if(!scope.scoped())return controlledModelCall(task, () -> callLlm(systemPrompt,userMessage));
        var streamer=scopedStreamer;
        if(streamer==null)throw new com.zxl.zxlaiagent.security.SecurityFailure("SCOPED_MODEL_UNAVAILABLE",503);
        var transportBoundary=new com.zxl.zxlaiagent.security.execution.OperationBoundary(){
            public void check(com.zxl.zxlaiagent.security.Phase phase){scope.control().check();scope.boundary().check(phase);}
            public <T>T start(com.zxl.zxlaiagent.security.Phase phase,Supplier<T> operation){
                // Authority gate first, then a short control admission. Never hold either over the response wait.
                return scope.boundary().start(phase,()->scope.control().admit(operation));
            }
        };
        return runtime.modelCall(task,scope,()->{
            scope.control().check();
            var response=streamer.stream(systemPrompt,java.util.List.of(new org.springframework.ai.chat.messages.UserMessage(userMessage)),
                    new org.springframework.ai.tool.ToolCallback[0],(type,text)->{},
                    ()->Thread.currentThread().isInterrupted()||scope.control().stopReason()!=null||task.snapshot().status()!=TaskStatus.IN_PROGRESS,
                    transportBoundary);
            scope.control().check();
            var output=response.getResult().getOutput();
            if(!output.getToolCalls().isEmpty())throw new WorkflowException("UNEXPECTED_MODEL_TOOL","model","当前步骤没有授权工具");
            if(output.getText()==null||output.getText().isBlank())throw new WorkflowException("EMPTY_MODEL_RESULT","model","模型没有返回内容");
            return output.getText();
        });
    }
    protected <T>T controlledModelCall(Task task,Supplier<T> action) {
        if (runtime != null) return runtime.modelCall(task, action);
        if (!TaskLifecycle.recordModelCall(task))
            throw new WorkflowException("TASK_ALREADY_TERMINAL", "tasks." + task.getId(), "任务已经结束，不能继续调用模型");
        return action.get();
    }
    protected <T>T controlledModelCall(Task task,ExecutionRuntime.ExecutionScope captured,Supplier<T> action){
        return captured.scoped()?runtime.modelCall(task,captured,action):controlledModelCall(task,action);
    }

    protected String callLlm(String systemPrompt, String userMessage) {
        return chatClient.prompt()
                .system(systemPrompt)
                .user(userMessage)
                .call()
                .content();
    }
}
