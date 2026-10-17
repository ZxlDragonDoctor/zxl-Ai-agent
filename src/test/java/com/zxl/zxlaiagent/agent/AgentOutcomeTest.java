package com.zxl.zxlaiagent.agent;

import com.zxl.zxlaiagent.agent.model.*;
import com.zxl.zxlaiagent.tools.CurrentTimeTool;
import com.zxl.zxlaiagent.tools.TerminateTool;
import com.zxl.zxlaiagent.tools.model.ToolResult;
import com.zxl.zxlaiagent.tools.support.ToolResults;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbacks;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.tool.annotation.Tool;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Real ToolCallingManager, controlled model responses, and no external services. */
class AgentOutcomeTest {
    public static class ThrowingTool {
        @Tool(description = "controlled failure")
        public String explode() { throw new IllegalStateException("受控工具执行异常"); }
    }

    @Test void annotatedToolExceptionAbortsRatherThanBecomingPlainText() {
        var agent = new ScriptedAgent(ToolCallbacks.from(new ThrowingTool()), call("explode", "{}"),
                new AssistantMessage("不应执行到此回答"));
        agent.run("执行异常");
        assertEquals(AgentEndReason.TOOL_ERROR, agent.getEndReason());
        assertEquals(1, agent.getCurrentStep());
        assertEquals(1, agent.responses.size());
    }
    @Test void outputCancellationIsNotMisclassifiedAsAModelFailure() throws Exception {
        var agent = agent();
        agent.cancelOutput = true;
        agent.runStream("输出连接中断");
        assertTrue(agent.done.await(5, TimeUnit.SECONDS));
        assertEquals(AgentEndReason.OUTPUT_INTERRUPTED, agent.getEndReason());
    }
    @Test void normalAnswerHasAnExplicitReason() {
        var agent = agent(new AssistantMessage("你好"));
        agent.run("你好");
        assertEquals(AgentEndReason.ANSWER_READY, agent.getEndReason());
    }

    @Test void remainingWorkAtBudgetIsNotAnAnswer() {
        var agent = agent(call("getCurrentTime", "{\"zoneId\":\"UTC\"}"));
        agent.setMaxSteps(1);
        agent.run("查询时间");
        assertEquals(AgentEndReason.STEP_LIMIT, agent.getEndReason());
    }

    @Test void terminateToolHasItsOwnReason() {
        var agent = agent(call("doTerminate", "{}"));
        agent.run("终止执行");
        assertEquals(AgentEndReason.TERMINATED_BY_TOOL, agent.getEndReason());
    }

    @Test void modelFailureSurvivesOuterExceptionHandlers() {
        var agent = agent();
        agent.run("模型不可用");
        assertEquals(AgentState.ERROR, agent.getState());
        assertEquals(AgentEndReason.MODEL_ERROR, agent.getEndReason());
    }

    @Test void unknownToolFailsAtExecution() {
        var agent = agent(call("missingTool", "{}"));
        agent.run("调用不存在的工具");
        assertEquals(AgentEndReason.TOOL_ERROR, agent.getEndReason());
    }

    @Test void businessFailureIsReturnedToModelAndCanBeExplained() {
        var agent = agent(call("getCurrentTime", "{\"zoneId\":\"Mars/Olympus\"}"),
                new AssistantMessage("这个时区无效，请提供正确的时区。"));
        agent.run("查询无效时区");
        assertEquals(AgentEndReason.ANSWER_READY, agent.getEndReason());
        var responses = agent.getMessageList().stream().filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast).flatMap(message -> message.getResponses().stream()).toList();
        assertEquals(1, responses.size());
        assertEquals("INVALID_ZONE", ToolResults.read(responses.get(0).responseData()).path("code").asText());
        assertTrue(agent.events.stream().anyMatch(event -> "TOOL_FAILED".equals(event.type())));
    }

    @Test void directSuccessfulResultEndsWithoutAnotherModelCall() {
        var callback = directCallback(true);
        var agent = new ScriptedAgent(new ToolCallback[]{callback}, call("direct", "{}"));
        agent.run("直接返回");
        assertEquals(AgentEndReason.TOOL_RETURNED, agent.getEndReason());
        assertTrue(agent.events.stream().anyMatch(event -> "ANSWER_DELTA".equals(event.type())));
    }

    @Test void directBusinessFailureStillReturnsToTheModel() {
        var agent = new ScriptedAgent(new ToolCallback[]{directCallback(false)}, call("direct", "{}"),
                new AssistantMessage("操作失败，请修正参数。"));
        agent.run("直接工具失败");
        assertEquals(AgentEndReason.ANSWER_READY, agent.getEndReason());
        assertTrue(agent.getMessageList().stream().anyMatch(ToolResponseMessage.class::isInstance));
    }

    @Test void streamExposesTheReasonAndRetainsCompletedToolsWhenModelFails() throws Exception {
        var agent = agent(call("getCurrentTime", "{\"zoneId\":\"UTC\"}"));
        agent.runStream("工具成功后模型断开");
        assertTrue(agent.done.await(5, TimeUnit.SECONDS));
        assertEquals(AgentEndReason.MODEL_ERROR, agent.getEndReason());
        assertTrue(agent.events.stream().anyMatch(event -> "TOOL_COMPLETED".equals(event.type())));
        var terminal = agent.events.stream().filter(event -> "RUN_FAILED".equals(event.type())).findFirst().orElseThrow();
        assertEquals(AgentEndReason.MODEL_ERROR, terminal.endReason());
        assertEquals("error", terminal.status());
    }

    private static ToolCallback directCallback(boolean success) {
        return new ToolCallback() {
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("direct").description("local direct result")
                        .inputSchema("{\"type\":\"object\",\"properties\":{}}").build();
            }
            public ToolMetadata getToolMetadata() { return ToolMetadata.builder().returnDirect(true).build(); }
            public String call(String input) {
                return ToolResults.json(success ? ToolResult.ok("完成", Map.of("path", "local.txt"))
                        : ToolResult.fail("INVALID_INPUT", "请修正参数"));
            }
        };
    }

    private static ScriptedAgent agent(AssistantMessage... responses) {
        return new ScriptedAgent(ToolCallbacks.from(new CurrentTimeTool(), new TerminateTool()), responses);
    }
    private static AssistantMessage call(String name, String arguments) {
        return new AssistantMessage("", Map.of(), List.of(new AssistantMessage.ToolCall(
                UUID.randomUUID().toString(), "function", name, arguments)));
    }
    private static final class ScriptedAgent extends ToolCallAgent {
        private final Deque<AssistantMessage> responses;
        private final List<AgentExecutionEvent> events = new CopyOnWriteArrayList<>();
        private final CountDownLatch done = new CountDownLatch(1);
        private boolean cancelOutput;
        ScriptedAgent(ToolCallback[] callbacks, AssistantMessage... responses) {
            super(callbacks);
            this.responses = new ArrayDeque<>(List.of(responses));
        }
        @Override public boolean think() {
            if (cancelOutput) throw new CancellationException("输出连接已关闭");
            var response = responses.pollFirst();
            if (response == null) throw new IllegalStateException("受控模型不可用");
            setToolCallChatResponse(new ChatResponse(List.of(new Generation(response))));
            if (response.getToolCalls().isEmpty()) getMessageList().add(response);
            return !response.getToolCalls().isEmpty();
        }
        @Override protected void emitExecutionEvent(AgentExecutionEvent event) {
            events.add(event);
            super.emitExecutionEvent(event);
        }
        @Override protected void cleanup() { done.countDown(); }
    }
}
