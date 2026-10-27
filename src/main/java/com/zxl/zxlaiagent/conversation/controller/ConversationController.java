package com.zxl.zxlaiagent.conversation.controller;

import com.zxl.zxlaiagent.conversation.model.ConversationDetail;
import com.zxl.zxlaiagent.conversation.model.ConversationSummary;
import com.zxl.zxlaiagent.conversation.service.ConversationService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.util.List;

@RestController
@RequestMapping("/conversations")
@ConditionalOnProperty(name = "agent.security.mode", havingValue = "legacy-demo", matchIfMissing = true)
public class ConversationController {
    private final ConversationService service;
    public ConversationController(ConversationService service) { this.service = service; }

    public record CreateRequest(String title, String agentType) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationDetail create(@RequestBody(required = false) CreateRequest body) {
        var conversation = service.create(body == null ? null : body.title(), body == null ? null : body.agentType());
        return service.detail(conversation.id());
    }

    @GetMapping public List<ConversationSummary> list() { return service.list(); }

    @GetMapping("/{id}") public ConversationDetail get(@PathVariable String id) {
        return service.detail(ConversationService.parseId(id));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) { service.delete(ConversationService.parseId(id)); }
}
