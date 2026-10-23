package com.zxl.zxlaiagent.conversation.context;

import com.zxl.zxlaiagent.conversation.service.AgentConversationService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import com.zxl.zxlaiagent.conversation.ConversationException;
import org.springframework.http.ResponseEntity;
import java.util.Map;

/** Same local single-user scope as existing conversation endpoints; deliberately demo-only. */
@RestController
@Profile("lesson11-demo")
@RequestMapping("/ai/learning/context")
public class ContextPreviewController {
    private final AgentConversationService service;
    public ContextPreviewController(AgentConversationService service) { this.service = service; }
    public record Request(String conversationId, String message) {}
    public record Preview(ContextAssembler.Summary summary, ContextAssembler.Report budget) {}
    @PostMapping("/preview")
    public Preview preview(@RequestBody Request request) {
        var plan = service.previewContext(request.conversationId(), request.message());
        return new Preview(plan.summary(), plan.report());
    }
    @ExceptionHandler(ConversationException.class)
    public ResponseEntity<Map<String, String>> error(ConversationException error) {
        return ResponseEntity.status(error.getStatus()).body(Map.of("code", error.getCode(), "message", error.getMessage()));
    }
}
