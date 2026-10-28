package com.zxl.zxlaiagent.controller;

import com.zxl.zxlaiagent.app.LoveApp;
import com.zxl.zxlaiagent.conversation.service.AgentConversationService;
import jakarta.annotation.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;

@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "agent.security.mode", havingValue = "legacy-demo", matchIfMissing = true)
@RequestMapping("/ai")
public class AiController {

    @Resource
    private LoveApp loveApp;

    @Resource
    private AgentConversationService agentConversations;

    @GetMapping("/love_app/chat/sync")
    public String doChatWithLoveAppSync(String message, String chatId) {
        return loveApp.doChat(message, chatId);
    }

    @GetMapping(value = "/love_app/chat/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> doChatWithLoveAppSSE(String message, String chatId) {
        return loveApp.doChatByStream(message, chatId);
    }
    @GetMapping(value = "/love_app/chat/sse2/")
    public Flux<ServerSentEvent<String>> doChatWithLoveAppSSE2(String message, String chatId) {
        return loveApp.doChatByStream(message, chatId)
                .map(chunk -> ServerSentEvent.<String>builder()
                        .data(chunk)
                        .build());
    }
    @GetMapping("/love_app/chat/sse/emitter")
    public SseEmitter doChatWithLoveAppSseEmitter(String message, String chatId) {
        // 创建一个超时时间较长的 SseEmitter
        SseEmitter emitter = new SseEmitter(180000L); // 3分钟超时
        // 获取 Flux 数据流并直接订阅
        loveApp.doChatByStream(message, chatId)
                .subscribe(
                        // 处理每条消息
                        chunk -> {
                            try {
                                emitter.send(chunk);
                            } catch (IOException e) {
                                emitter.completeWithError(e);
                            }
                        },
                        // 处理错误
                        emitter::completeWithError,
                        // 处理完成
                        emitter::complete
                );
        // 返回emitter
        return emitter;
    }

    /**
     * 流式调用 Manus 超级智能体
     *
     * @param message
     * @return
     */
    @GetMapping(value = {"/manus/chat", "/agent/chat"}, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter doChatWithManus(@RequestParam String message,
                                    @RequestParam(required = false) String conversationId,
                                    @RequestParam(required = false) String skillId,
                                    @RequestParam(required = false) String skillVersion) {
        var options = new com.zxl.zxlaiagent.conversation.agent.AgentRunOptions(skillId, skillVersion);
        return options.isEmpty() ? agentConversations.chat(message, conversationId) : agentConversations.chat(message, conversationId, options);
    }

    public record ManusChatRequest(String message, String conversationId, String skillId, String skillVersion) {
        public ManusChatRequest(String message, String conversationId) { this(message, conversationId, null, null); }
    }

    @PostMapping(value = {"/manus/chat", "/agent/chat"}, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter postChatWithManus(@RequestBody ManusChatRequest request) {
        var options = new com.zxl.zxlaiagent.conversation.agent.AgentRunOptions(request.skillId(), request.skillVersion());
        return options.isEmpty() ? agentConversations.chat(request.message(), request.conversationId()) : agentConversations.chat(request.message(), request.conversationId(), options);
    }

}
