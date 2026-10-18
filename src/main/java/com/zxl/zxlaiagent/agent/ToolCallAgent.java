package com.zxl.zxlaiagent.agent;

import cn.hutool.core.collection.CollUtil;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.zxl.zxlaiagent.agent.model.AgentExecutionEvent;
import com.zxl.zxlaiagent.agent.model.AgentState;
import com.zxl.zxlaiagent.agent.model.AgentEndReason;
import com.zxl.zxlaiagent.tools.support.ToolResults;
import com.zxl.zxlaiagent.tools.model.ToolResult;
import com.zxl.zxlaiagent.agent.stream.DashScopeAgentStreamer;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 处理工具调用的基础代理类，具体实现了 think 和 act 方法，可以用作创建实例的父类
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Slf4j
public class ToolCallAgent extends ReActAgent {

    // 可用的工具  
    private final ToolCallback[] availableTools;

    // 保存了工具调用信息的响应  
    private ChatResponse toolCallChatResponse;

    // 工具调用管理者  
    private final ToolCallingManager toolCallingManager;

    // 禁用内置的工具调用机制，自己维护上下文  
    private final ChatOptions chatOptions;

    private DashScopeAgentStreamer streamClient;

    public ToolCallAgent(ToolCallback[] availableTools) {
        super();
        this.availableTools = availableTools;
        this.toolCallingManager = ToolCallingManager.builder()
                .toolExecutionExceptionProcessor(DefaultToolExecutionExceptionProcessor.builder().alwaysThrow(true).build())
                .build();
        // 禁用 Spring AI 内置的工具调用机制，自己维护选项和消息上下文  
        this.chatOptions = DashScopeChatOptions.builder()
                .withProxyToolCalls(true)
                .build();
    }

    /**
     * 处理当前状态并决定下一步行动
     *
     * @return 是否需要执行行动
     */
    @Override
    public boolean think() {
        // 本轮执行提示仅用于 Prompt，不伪装成用户消息写入可恢复历史。
        List<Message> messageList = new ArrayList<>(getMessageList());
        if (getNextStepPrompt() != null && !getNextStepPrompt().isEmpty()) {
            messageList.add(new SystemMessage(getNextStepPrompt()));
        }
        Prompt prompt = new Prompt(messageList, chatOptions);
        try {
            // 获取带工具选项的响应
            ChatResponse chatResponse = streamClient != null
                    ? streamClient.stream(getSystemPrompt(), messageList, availableTools,
                            (type, text) -> emitExecutionEvent(AgentExecutionEvent.of(type, getCurrentStep(), text, "running")),
                            this::isStreamClosed)
                    : getChatClient().prompt(prompt)
                    .system(getSystemPrompt())
                    .tools(availableTools)
                    .call()
                    .chatResponse();
            // 记录响应，用于 Act  // 获取AI选择的工具和参数
            this.toolCallChatResponse = chatResponse;
            AssistantMessage assistantMessage = chatResponse.getResult().getOutput(); //获取助手消息
            // 输出提示信息
            String result = assistantMessage.getText();
            List<AssistantMessage.ToolCall> toolCallList = assistantMessage.getToolCalls();
            log.info(getName() + "的思考: " + result);
            log.info(getName() + "选择了 " + toolCallList.size() + " 个工具来使用");
            String toolCallInfo = toolCallList.stream()
                    .map(toolCall -> String.format("工具名称：%s，参数：%s",
                            toolCall.name(),
                            toolCall.arguments())
                    )
                    .collect(Collectors.joining("\n"));
            log.info(toolCallInfo);
            for (AssistantMessage.ToolCall call : toolCallList) {
                emitExecutionEvent(AgentExecutionEvent.tool("TOOL_REQUESTED", getCurrentStep(), call.id(),
                        call.name(), call.arguments(), null, "requested", null));
            }
            if (toolCallList.isEmpty()) {
                // 只有不调用工具时，才记录助手消息
                getMessageList().add(assistantMessage);
                return false;
            } else {
                // 需要调用工具时，无需记录助手消息，因为调用工具时会自动记录
                return true;
            }
        } catch (CancellationException e) {
            throw e;
        } catch (Exception e) {
            log.error(getName() + "的思考过程遇到了问题: " + e.getMessage());
            failRun(AgentEndReason.MODEL_ERROR);
            if (e instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("模型调用失败", e);
        }

    }

    /**
     * 执行工具调用并处理结果
     *
     * @return 执行结果
     */
    @Override
    public String act() {
        if (!toolCallChatResponse.hasToolCalls()) {
            return "没有工具调用";
        }
        // 调用工具
        // The execution manager must receive the same callbacks that were offered to the model.
        // Wrapping here records actual invocation boundaries, including multiple calls of one tool.
        List<AssistantMessage.ToolCall> requested = toolCallChatResponse.getResult().getOutput().getToolCalls();
        if (requested.size() > 1 && requested.stream().anyMatch(call -> "doTerminate".equals(call.name()))) {
            throw new IllegalArgumentException("终止工具必须单独调用，请先观察其他工具结果");
        }
        ToolCallback[] observedTools = Arrays.stream(availableTools)
                .map(tool -> (ToolCallback) new ObservedToolCallback(tool, requested))
                .toArray(ToolCallback[]::new);
        DashScopeChatOptions executionOptions = new DashScopeChatOptions();
        executionOptions.setProxyToolCalls(true);
        executionOptions.setFunctionCallbacks(new ArrayList<>(List.of(observedTools)));
        Prompt prompt = new Prompt(getMessageList(), executionOptions);
        ToolExecutionResult toolExecutionResult = toolCallingManager.executeToolCalls(prompt, toolCallChatResponse);
        // 记录消息上下文，conversationHistory 已经包含先前消息,助手消息和工具调用返回的结果
        setMessageList(toolExecutionResult.conversationHistory());
        // 当前工具调用的结果
        ToolResponseMessage toolResponseMessage = (ToolResponseMessage) CollUtil.getLast(toolExecutionResult.conversationHistory());
        String results = toolResponseMessage.getResponses().stream()
                .map(response -> "工具 " + response.name() + " 返回: " + response.responseData())
                .collect(Collectors.joining("\n"));
// 判断是否调用了终止工具
        boolean terminateToolCalled = toolResponseMessage.getResponses().stream()
                .anyMatch(response -> "doTerminate".equals(response.name())
                        && ToolResults.read(response.responseData()).path("success").asBoolean());
        if (terminateToolCalled) {
            finishRun(AgentEndReason.TERMINATED_BY_TOOL);
        } else if (toolExecutionResult.returnDirect() && toolResponseMessage.getResponses().stream()
                .allMatch(response -> ToolResults.read(response.responseData()).path("success").asBoolean())) {
            String direct = toolResponseMessage.getResponses().stream().map(response -> {
                var value = ToolResults.read(response.responseData());
                return value.path("message").asText() + "\n" + value.path("data").toPrettyString();
            }).collect(Collectors.joining("\n\n"));
            getMessageList().add(new AssistantMessage(direct));
            emitExecutionEvent(AgentExecutionEvent.of("ANSWER_DELTA", getCurrentStep(), direct, "running"));
            finishRun(AgentEndReason.TOOL_RETURNED);
        }
        log.info(results);
        return results;
    }

    private class ObservedToolCallback implements ToolCallback {
        private final ToolCallback delegate;
        private final Deque<AssistantMessage.ToolCall> pending;

        ObservedToolCallback(ToolCallback delegate, List<AssistantMessage.ToolCall> requested) {
            this.delegate = delegate;
            this.pending = new ArrayDeque<>(requested.stream()
                    .filter(call -> delegate.getToolDefinition().name().equals(call.name())).toList());
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return delegate.getToolMetadata();
        }

        @Override
        public String call(String input) {
            return observe(input, () -> delegate.call(input));
        }

        @Override
        public String call(String input, ToolContext context) {
            return observe(input, () -> delegate.call(input, context));
        }

        private String observe(String input, Supplier<String> execution) {
            if (isStreamClosed()) throw new CancellationException("输出连接已关闭");
            AssistantMessage.ToolCall requested = pending.pollFirst();
            String callId = requested == null ? java.util.UUID.randomUUID().toString() : requested.id();
            String name = delegate.getToolDefinition().name();
            emitExecutionEvent(AgentExecutionEvent.tool("TOOL_STARTED", getCurrentStep(), callId,
                    name, input, null, "running", null));
            long started = System.nanoTime();
            try {
                String result = execution.get();
                boolean error = !ToolResults.read(result).path("success").asBoolean();
                emitExecutionEvent(AgentExecutionEvent.tool(error ? "TOOL_FAILED" : "TOOL_COMPLETED",
                        getCurrentStep(), callId, name, input, result, error ? "error" : "completed",
                        (System.nanoTime() - started) / 1_000_000));
                return result;
            } catch (CancellationException e) {
                throw e;
            } catch (RuntimeException e) {
                emitExecutionEvent(AgentExecutionEvent.tool("TOOL_FAILED", getCurrentStep(), callId,
                        name, input, ToolResults.json(ToolResult.fail("TOOL_EXECUTION_ERROR", "工具执行异常")),
                        "error", (System.nanoTime() - started) / 1_000_000));
                throw e;
            }
        }
    }

}
