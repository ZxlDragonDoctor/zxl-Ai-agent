package com.zxl.zxlaiagent.security.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@ConditionalOnProperty(name = "agent.security.mode", havingValue = "required")
public final class SecurityHealthController {
    /** Liveness only: no database, actor, model, credential or deployment metadata. */
    @GetMapping("/health/live") public Map<String, String> live() { return Map.of("status", "alive"); }
}
