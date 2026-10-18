package com.zxl.zxlaiagent.agent;

import com.zxl.zxlaiagent.agent.model.AgentState;
import com.zxl.zxlaiagent.agent.model.AgentExecutionEvent;
import com.zxl.zxlaiagent.agent.model.AgentEndReason;
import com.zxl.zxlaiagent.agent.model.AgentRunSnapshot;
import lombok.Data;
import lombok.AccessLevel;
import lombok.Setter;
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

    protected void emitExecutionEvent(AgentExecutionEvent event) {
        event = event.withIdentity(conversationId, runId);
        if (eventObserver != null) eventObserver.accept(event);
        if (executionListener == null) return;
        if (streamClosed.get()) throw new CancellationException("输出连接已关闭");
        executionListener.accept(event);
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
        SseEmitter emitter = new SseEmitter(300000L); // 5分钟超时
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
                    try { emitter.send("Step " + currentStep + ": " + result); }
                    catch (IOException e) { throw new CancellationException("输出连接已关闭"); }
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
                        emitter.complete();
                    } catch (Exception sendFailure) {
                        streamClosed.set(true);
                    }
                }
                cleanup();
            }
        });
        return emitter;
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
        state = AgentState.RUNNING;
        messageList.add(new UserMessage(userPrompt));
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
