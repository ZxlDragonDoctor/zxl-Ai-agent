package com.zxl.zxlaiagent.agent;

import com.zxl.zxlaiagent.agent.model.AgentState;
import com.zxl.zxlaiagent.agent.model.AgentExecutionEvent;
import com.zxl.zxlaiagent.agent.model.AgentEndReason;
import com.zxl.zxlaiagent.agent.model.AgentRunSnapshot;
import lombok.Data;
import lombok.AccessLevel;
import lombok.Setter;
import lombok.Getter;
import com.zxl.zxlaiagent.security.Phase;
import com.zxl.zxlaiagent.security.execution.OperationBoundary;
import com.zxl.zxlaiagent.agent.stream.PreparedAgentStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.util.StringUtils;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.concurrent.*;

/**
 * 抽象基础代理类，用于管理代理状态和执行流程。  
 *   
 * 提供状态转换、内存管理和基于步骤的执行循环的基础功能。  
 * 子类必须实现step方法。  
 */  
@Data
@Slf4j
public abstract class BaseAgent {  
  
    // 核心属性  
    private String name;  
  
    // 提示  
    private String systemPrompt;  
    private String nextStepPrompt;  
  
    // 状态  
    private volatile AgentState state = AgentState.IDLE;
    @Setter(AccessLevel.NONE)
    private volatile AgentEndReason endReason;
  
    // 执行控制  
    private int maxSteps = 10;  
    private int currentStep = 0;  
  
    // LLM  
    private ChatClient chatClient;
  
    // Memory（需要自主维护会话上下文）  
    private List<Message> messageList = new ArrayList<>();

    private transient Consumer<AgentExecutionEvent> executionListener;
    private transient Consumer<AgentExecutionEvent> eventObserver;
    private transient Consumer<AgentRunSnapshot> completionListener;
    private String conversationId;
    private String runId;
    @Setter(AccessLevel.NONE)
    private String failureMessage;
    private final AtomicBoolean streamClosed = new AtomicBoolean(false);
    private final AtomicBoolean completionNotified = new AtomicBoolean(false);
    @Getter(AccessLevel.NONE) @Setter(AccessLevel.NONE)
    private transient OperationBoundary operationBoundary = OperationBoundary.unrestricted();
    @Getter(AccessLevel.NONE) @Setter(AccessLevel.NONE)
    private boolean operationBoundaryBound;
    @Getter(AccessLevel.NONE) @Setter(AccessLevel.NONE)
    private boolean executionClaimed;
    // Fixed capacity across runs. Slow sockets cannot create unlimited threads or queued secrets.
    private static final ThreadPoolExecutor PUBLICATIONS = new ThreadPoolExecutor(4, 4, 0,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(32), task -> {
                Thread thread = new Thread(task, "agent-publication"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private static final ThreadPoolExecutor CLOSURES = new ThreadPoolExecutor(1, 1, 0,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(32), task -> {
                Thread thread = new Thread(task, "agent-stream-close"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    /** Identity is captured on the new per-run instance, before asynchronous execution starts. */
    public synchronized void bindOperationBoundary(OperationBoundary boundary) {
        if (executionClaimed || state != AgentState.IDLE || operationBoundaryBound)
            throw new IllegalStateException("只能向未启动的新 Agent 绑定一次执行身份");
        operationBoundary = java.util.Objects.requireNonNull(boundary);
        operationBoundaryBound = true;
    }

    protected final OperationBoundary operationBoundary() { return operationBoundary; }
    protected final boolean hasOperationBoundary() { return operationBoundaryBound; }

    protected void emitExecutionEvent(AgentExecutionEvent event) {
        event = event.withIdentity(conversationId, runId);
        final AgentExecutionEvent captured = event;
        publish(() -> {
            if (eventObserver != null) operationBoundary.start(Phase.RESULT_PUBLISH, () -> {
                eventObserver.accept(captured); return null;
            });
            if (executionListener != null) {
                operationBoundary.check(Phase.RESULT_PUBLISH);
                executionListener.accept(captured);
            }
        });
    }

    /** The PG linearization point is bounded queue admission. Consumer checks again before
     * capture/send. Spring MVC and TCP cannot share the PG transaction: a send already started
     * may finish after revocation. Formal MVC starts prepared streams only after handler
     * initialization, preventing Spring's early-send buffer from retaining authorized frames.
     * We do not hold the epoch lock while waiting for a socket. */
    private void publish(Runnable action) {
        if (streamClosed.get()) throw new CancellationException("输出连接已关闭");
        if (!operationBoundaryBound) { action.run(); return; }
        FutureTask<Void> task = new FutureTask<>(() -> {
            if (streamClosed.get() || Thread.currentThread().isInterrupted())
                throw new CancellationException("输出连接已关闭");
            operationBoundary.check(Phase.RESULT_PUBLISH);
            action.run(); return null;
        });
        try {
            operationBoundary.start(Phase.RESULT_PUBLISH, () -> { PUBLICATIONS.execute(task); return null; });
            task.get(2, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); task.cancel(true); streamClosed.set(true);
            throw new CancellationException("输出接收已停止");
        } catch (ExecutionException error) {
            if (error.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("输出发布失败", error.getCause());
        } catch (TimeoutException | RejectedExecutionException error) {
            task.cancel(true); PUBLICATIONS.remove(task); streamClosed.set(true);
            throw new CancellationException("输出接收超时或繁忙");
        }
    }

    public boolean isStreamClosed() {
        return streamClosed.get();
    }
  
    /**  
     * 运行代理  
     *  
     * @param userPrompt 用户提示词  
     * @return 执行结果  
     */  
    public String run(String userPrompt) {  
        claimExecution();
        startRun(userPrompt);
        // 保存结果列表  
        List<String> results = new ArrayList<>();
        try {  
            for (int i = 0; i < maxSteps && state == AgentState.RUNNING; i++) {
                int stepNumber = i + 1;  
                currentStep = stepNumber;  
                log.info("Executing step " + stepNumber + "/" + maxSteps);  
                // 单步执行  
                String stepResult = step();  
                String result = "Step " + stepNumber + ": " + stepResult;  
                results.add(result);  
            }

            completeRun();
            notifyCompletion();
            if (endReason == AgentEndReason.PERSISTENCE_ERROR) return "执行错误: " + failureMessage;
            if (endReason == AgentEndReason.STEP_LIMIT) {
                results.add("Terminated: Reached max steps (" + maxSteps + ")");  
            }  
            return String.join("\n", results);  
        } catch (Exception e) {  
            failRun(AgentEndReason.EXECUTION_ERROR);
            failureMessage = e.getMessage();
            notifyCompletion();
            log.error("Error executing agent", e);  
            return "执行错误" + e.getMessage();  
        } finally {  
            // 清理资源  
            this.cleanup();  
        }  
    }

    /**
     * 运行代理（流式输出）
     *
     * @param userPrompt 用户提示词
     * @return SseEmitter实例
     */
    public SseEmitter runStream(String userPrompt) {
        if (operationBoundaryBound) throw new IllegalStateException("正式 Agent 必须使用 prepareStream 并在 MVC 就绪后激活");
        PreparedAgentStream prepared = prepareStream(userPrompt);
        prepared.activate();
        return prepared;
    }

    /** Formal callers must attach this stream through PreparedAgentStreamInterceptor. */
    public PreparedAgentStream prepareStream(String userPrompt) {
        claimExecution();
        // The closure captures only this run's explicit boundary, never thread-local identity.
        java.util.concurrent.atomic.AtomicReference<PreparedAgentStream> reference = new java.util.concurrent.atomic.AtomicReference<>();
        PreparedAgentStream emitter = new PreparedAgentStream(300000L,
                () -> executeStream(userPrompt, reference.get()), () -> {
                    streamClosed.set(true);
                    failRun(AgentEndReason.OUTPUT_INTERRUPTED);
                    failureMessage = "输出连接未就绪，执行未启动";
                    try { notifyCompletion(); } finally { cleanup(); }
                });
        reference.set(emitter);
        executionListener = event -> {
            try {
                emitter.send(SseEmitter.event().name("agent").data(event, MediaType.APPLICATION_JSON));
            } catch (IOException e) {
                streamClosed.set(true);
                throw new CancellationException("输出连接已关闭");
            }
        };
        // 输出关闭只发出信号。完成保存、资源清理与释放执行权均由工作线程负责。
        emitter.onTimeout(() -> streamClosed.set(true));
        emitter.onError(error -> streamClosed.set(true));
        emitter.onCompletion(() -> streamClosed.set(true));
        return emitter;
    }

    private void executeStream(String userPrompt, SseEmitter emitter) {
        CompletableFuture.runAsync(() -> {
            try {
                startRun(userPrompt);
                emitExecutionEvent(AgentExecutionEvent.of("RUN_STARTED", 0, null, "running"));
                for (int i = 0; i < maxSteps && state == AgentState.RUNNING && !streamClosed.get(); i++) {
                    currentStep = i + 1;
                    emitExecutionEvent(AgentExecutionEvent.of("STEP_STARTED", currentStep, null, "running"));
                    String result = step();
                    emitExecutionEvent(AgentExecutionEvent.of("STEP_COMPLETED", currentStep, null, "completed"));
                    // Keep the original text events for existing SSE clients.
                    final String raw = "Step " + currentStep + ": " + result;
                    publish(() -> {
                        try { emitter.send(raw); }
                        catch (IOException error) { throw new CancellationException("输出连接已关闭"); }
                    });
                }
                if (streamClosed.get()) failRun(AgentEndReason.OUTPUT_INTERRUPTED);
                else completeRun();
            } catch (CancellationException e) {
                streamClosed.set(true);
                failRun(AgentEndReason.OUTPUT_INTERRUPTED);
                failureMessage = "输出接收已停止，请核对工具实际执行结果";
            } catch (Exception e) {
                failRun(AgentEndReason.EXECUTION_ERROR);
                failureMessage = e.getMessage();
                log.error("执行智能体失败", e);
            } finally {
                // 保存成功以后才发送终态，防止页面先宣布成功、实际历史却未落盘。
                notifyCompletion();
                if (!streamClosed.get()) {
                    try {
                        String detail = endReason == AgentEndReason.STEP_LIMIT
                                ? "达到最大执行步数，执行已停止" : failureMessage;
                        emitExecutionEvent(AgentExecutionEvent.terminal(currentStep, detail, endReason));
                    } catch (Exception sendFailure) {
                        streamClosed.set(true);
                    }
                }
                // A rejected terminal must still release the HTTP response without sending its detail.
                streamClosed.set(true);
                if (!operationBoundaryBound) {
                    try { emitter.complete(); } finally { cleanup(); }
                }
                else {
                    cleanup();
                    // complete() also takes the emitter monitor held by a slow send; keep lease
                    // release independent. Servlet timeout remains the final socket backstop.
                    try { CLOSURES.execute(emitter::complete); }
                    catch (RejectedExecutionException busy) { /* bounded closure backlog; servlet timeout */ }
                }
            }
        });
    }

    private void notifyCompletion() {
        if (!completionNotified.compareAndSet(false, true)) return;
        if (endReason == null) failRun(AgentEndReason.EXECUTION_ERROR);
        if (completionListener == null) return;
        try {
            completionListener.accept(new AgentRunSnapshot(messageList, endReason, failureMessage));
        } catch (RuntimeException e) {
            endReason = AgentEndReason.PERSISTENCE_ERROR;
            state = AgentState.ERROR;
            failureMessage = "会话保存失败，本次结果未确认持久化；请检查服务端存储后刷新";
            log.error("Agent completion persistence failed", e);
        }
    }

    /** 仅新建且未运行的 Agent 可以载入历史，不能重置已结束实例继续执行。 */
    public synchronized void restoreMessages(List<Message> history) {
        if (state != AgentState.IDLE) throw new IllegalStateException("只能向新 Agent 恢复历史");
        messageList = new ArrayList<>(history);
    }


    /**  
     * 执行单个步骤  
     *  
     * @return 步骤执行结果  
     */  
    public abstract String step();

    /** 预算只能在空闲时设置；校验失败不改变原预算和运行状态。 */
    public synchronized void setMaxSteps(int maxSteps) {
        if (maxSteps < 1 || maxSteps > 50) {
            throw new IllegalArgumentException("maxSteps 必须在 1 到 50 之间");
        }
        if (state != AgentState.IDLE) {
            throw new IllegalStateException("只能在 IDLE 状态设置 maxSteps，当前状态: " + state);
        }
        this.maxSteps = maxSteps;
    }

    /** 与预算 setter 共用锁，保证开始执行与修改预算不会交错。 */
    private synchronized void startRun(String userPrompt) {
        if (state != AgentState.IDLE) {
            throw new IllegalStateException("无法从当前状态运行代理: " + state);
        }
        if (!StringUtils.hasText(userPrompt)) {
            throw new IllegalArgumentException("请输入需要完成的任务");
        }
        operationBoundary.check(Phase.DEQUEUE);
        state = AgentState.RUNNING;
        messageList.add(new UserMessage(userPrompt));
    }

    private synchronized void claimExecution() {
        if (executionClaimed || state != AgentState.IDLE) throw new IllegalStateException("Agent 已启动");
        executionClaimed = true;
    }

    private boolean hasReachedStepLimit() {
        return state == AgentState.RUNNING && currentStep >= maxSteps;
    }

    protected synchronized void finishRun(AgentEndReason reason) {
        if (endReason == null) endReason = reason;
        state = endReason.isFailure() ? AgentState.ERROR : AgentState.FINISHED;
    }

    protected synchronized void failRun(AgentEndReason reason) {
        if (endReason == null || !endReason.isFailure()) endReason = reason;
        state = AgentState.ERROR;
    }

    private void completeRun() {
        if (state == AgentState.ERROR) {
            failRun(AgentEndReason.EXECUTION_ERROR);
            throw new IllegalStateException("Agent 执行进入错误状态");
        }
        if (hasReachedStepLimit()) finishRun(AgentEndReason.STEP_LIMIT);
        else if (endReason == null) finishRun(AgentEndReason.ANSWER_READY);
    }

    /**  
     * 清理资源  
     */  
    protected void cleanup() {  
        // 子类可以重写此方法来清理资源  
    }  
}
