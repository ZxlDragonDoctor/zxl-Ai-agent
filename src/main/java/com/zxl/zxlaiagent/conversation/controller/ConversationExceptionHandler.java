package com.zxl.zxlaiagent.conversation.controller;

import com.zxl.zxlaiagent.controller.AiController;
import com.zxl.zxlaiagent.conversation.ConversationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.Map;

@Slf4j
@RestControllerAdvice(assignableTypes = {ConversationController.class, AiController.class})
public class ConversationExceptionHandler {
    @ExceptionHandler(ConversationException.class)
    public ResponseEntity<Map<String, String>> handle(ConversationException error) {
        if (error.getStatus().is5xxServerError()) log.error("Conversation operation failed: {}", error.getCode(), error);
        return ResponseEntity.status(error.getStatus()).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", error.getCode(), "message", error.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> invalidJson(HttpMessageNotReadableException error) {
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", "INVALID_CONVERSATION_REQUEST", "message", "请求正文不是有效 JSON"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalidSelection(IllegalArgumentException error) {
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", "INVALID_AGENT_REQUEST", "message", "请求参数不符合当前 Agent 的约束"));
    }
}
