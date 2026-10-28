package com.zxl.zxlaiagent.agent.stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import reactor.core.Disposable;
import com.zxl.zxlaiagent.security.Phase;
import com.zxl.zxlaiagent.security.execution.OperationBoundary;

/** Stream reasoning/content without upgrading the rest of the M6 application. */
@Component
public class DashScopeAgentStreamer {
    private final ObjectMapper mapper;
    private final WebClient client;
    private final String model;
    private final boolean thinkingEnabled;
    private final int thinkingBudget;

    public DashScopeAgentStreamer(ObjectMapper mapper,
            @Value("${spring.ai.dashscope.api-key}") String apiKey,
            @Value("${agent.manus.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}") String baseUrl,
            @Value("${spring.ai.dashscope.chat.options.model:qwen-plus}") String model,
            @Value("${agent.manus.thinking-enabled:true}") boolean thinkingEnabled,
            @Value("${agent.manus.thinking-budget:1024}") int thinkingBudget) {
        this.mapper = mapper;
        this.model = model;
        this.thinkingEnabled = thinkingEnabled;
        this.thinkingBudget = thinkingBudget;
        this.client = WebClient.builder()
                .baseUrl(baseUrl.replaceAll("/+$", ""))
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(512 * 1024))
                .build();
    }

    public ChatResponse stream(String systemPrompt, List<Message> history, ToolCallback[] tools,
                               BiConsumer<String, String> onDelta, BooleanSupplier cancelled) {
        return stream(systemPrompt, history, tools, onDelta, cancelled, OperationBoundary.unrestricted());
    }

    /** Authorization covers subscription start; response waiting never holds the authority transaction. */
    public ChatResponse stream(String systemPrompt, List<Message> history, ToolCallback[] tools,
                               BiConsumer<String, String> onDelta, BooleanSupplier cancelled, OperationBoundary boundary) {
        Objects.requireNonNull(boundary, "Explicit operation boundary required");
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", model);
        request.put("stream", true);
        request.put("messages", messages(systemPrompt, history));
        request.put("enable_thinking", thinkingEnabled);
        if (thinkingEnabled && thinkingBudget > 0) request.put("thinking_budget", thinkingBudget);
        if (tools.length > 0) {
            request.put("tools", java.util.Arrays.stream(tools).map(tool -> {
                var definition = tool.getToolDefinition();
                Map<String, Object> function = new LinkedHashMap<>();
                function.put("name", definition.name());
                function.put("description", definition.description());
                function.put("parameters", readJson(definition.inputSchema()));
                return Map.of("type", "function", "function", function);
            }).toList());
        }

        StringBuilder answer = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        Map<Integer, PendingToolCall> calls = new TreeMap<>();
        boolean[] sawCompletion = {false};
        var completed = new CompletableFuture<Void>();
        Disposable subscription = null;
        try {
            var frames = client.post().uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                    .doOnNext(event -> {
                        if (cancelled.getAsBoolean()) throw new CancellationException("输出连接已关闭");
                        boundary.check(Phase.RESULT_PUBLISH);
                        String data = event.data();
                        if (data == null || data.isBlank()) return;
                        if ("[DONE]".equals(data.trim())) {
                            sawCompletion[0] = true;
                            return;
                        }
                        JsonNode chunk = readJson(data);
                        if (chunk.has("error")) throw new IllegalStateException("模型服务返回了错误响应");
                        JsonNode choice = chunk.path("choices").path(0);
                        if (choice.isMissingNode()) return;
                        JsonNode finish = choice.path("finish_reason");
                        if (!finish.isNull() && !finish.isMissingNode()) {
                            String reason = finish.asText();
                            if ("length".equals(reason)) throw new IllegalStateException("模型输出达到长度限制，请缩小任务后重试");
                            if ("content_filter".equals(reason)) throw new IllegalStateException("模型服务未返回可展示的内容");
                            sawCompletion[0] = true;
                        }
                        JsonNode delta = choice.path("delta");
                        append(delta.path("reasoning_content"), reasoning, "THINKING_DELTA", onDelta);
                        append(delta.path("content"), answer, "ANSWER_DELTA", onDelta);
                        for (JsonNode tool : delta.path("tool_calls")) {
                            int index = tool.path("index").asInt(0);
                            PendingToolCall call = calls.computeIfAbsent(index, ignored -> new PendingToolCall());
                            // Later Qwen deltas contain id:""; retain the first actual identifier.
                            String id = tool.path("id").asText("");
                            if (!id.isBlank()) call.id = id;
                            JsonNode function = tool.path("function");
                            if (function.hasNonNull("name")) call.name.append(function.path("name").asText());
                            if (function.hasNonNull("arguments")) call.arguments.append(function.path("arguments").asText());
                        }
                    });
            if (cancelled.getAsBoolean()) throw new CancellationException("输出连接已关闭");
            subscription = boundary.start(Phase.MODEL_DISPATCH,
                    () -> frames.subscribe(ignored -> {}, completed::completeExceptionally, () -> completed.complete(null)));
            await(completed, cancelled);
        } catch (WebClientResponseException e) {
            throw new IllegalStateException("模型服务请求失败（HTTP " + e.getStatusCode().value()
                    + "），请检查模型配置、额度及服务状态", e);
        } catch (WebClientRequestException e) {
            throw new IllegalStateException("连接模型服务失败，请检查网络后重试", e);
        } finally {
            if (subscription != null) subscription.dispose();
        }
        boundary.check(Phase.RESULT_PUBLISH);
        if (!sawCompletion[0]) throw new IllegalStateException("模型输出连接提前中断，请重试");

        List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
        for (PendingToolCall call : calls.values()) {
            if (call.id == null || call.id.isBlank() || call.name.isEmpty()) throw new IllegalStateException("模型返回了不完整的工具调用");
            String arguments = call.arguments.isEmpty() ? "{}" : call.arguments.toString();
            if (!readJson(arguments).isObject()) throw new IllegalStateException("模型返回的工具参数不是 JSON 对象");
            toolCalls.add(new AssistantMessage.ToolCall(call.id, "function", call.name.toString(), arguments));
        }
        if (answer.isEmpty() && toolCalls.isEmpty()) throw new IllegalStateException("模型未返回回答或工具调用，请重试");
        AssistantMessage message = new AssistantMessage(answer.toString(), Map.of(), toolCalls);
        return new ChatResponse(List.of(new Generation(message)));
    }

    private static void await(CompletableFuture<Void> completed, BooleanSupplier cancelled) {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(4);
        try {
            while (true) {
                if (cancelled.getAsBoolean()) throw new CancellationException("模型输出接收已取消");
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new IllegalStateException("模型输出等待超时");
                try {
                    completed.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(50)), TimeUnit.NANOSECONDS);
                    return;
                } catch (TimeoutException polling) {
                    // Poll independently of incoming frames so a silent server cannot retain a cancelled run.
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException("模型输出接收已取消");
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("模型输出未完成", failed.getCause());
        }
    }

    private List<Map<String, Object>> messages(String systemPrompt, List<Message> history) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        for (Message message : history) {
            if (message instanceof ToolResponseMessage toolMessage) {
                for (var response : toolMessage.getResponses()) {
                    messages.add(Map.of("role", "tool", "tool_call_id", response.id(),
                            "content", response.responseData() == null ? "" : response.responseData()));
                }
                continue;
            }
            Map<String, Object> serialized = new LinkedHashMap<>();
            serialized.put("role", message.getMessageType().getValue());
            serialized.put("content", message.getText() == null ? "" : message.getText());
            if (message instanceof AssistantMessage assistant && !assistant.getToolCalls().isEmpty()) {
                serialized.put("tool_calls", assistant.getToolCalls().stream().map(call -> Map.of(
                        "id", call.id(), "type", "function", "function",
                        Map.of("name", call.name(), "arguments", call.arguments()))).toList());
            }
            messages.add(serialized);
        }
        return messages;
    }

    private void append(JsonNode value, StringBuilder accumulator, String type, BiConsumer<String, String> sink) {
        if (value.isTextual() && !value.asText().isEmpty()) {
            String text = value.asText();
            accumulator.append(text);
            sink.accept(type, text);
        }
    }

    private JsonNode readJson(String value) {
        try {
            return mapper.readTree(value);
        } catch (Exception e) {
            throw new IllegalStateException("模型或工具定义返回了无效的 JSON 数据", e);
        }
    }

    private static class PendingToolCall {
        String id;
        final StringBuilder name = new StringBuilder();
        final StringBuilder arguments = new StringBuilder();
    }
}
