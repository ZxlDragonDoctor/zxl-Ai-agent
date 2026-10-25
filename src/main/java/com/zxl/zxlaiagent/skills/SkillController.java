package com.zxl.zxlaiagent.skills;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.List;

/** Read-only catalogue. Publication and rollback are trusted local operations, never anonymous HTTP. */
@RestController
@RequestMapping("/ai/learning/skills")
public final class SkillController {
    private final SkillRegistry registry;
    public SkillController(SkillRegistry registry) { this.registry = registry; }
    public record Directory(String defaultVersion, List<SkillRegistry.View> versions, boolean administrationEnabled) { }
    @GetMapping public Directory list() {
        return new Directory(registry.defaultVersion("it-ticket-analysis"), registry.list(), false);
    }
    @GetMapping("/{id}/{version}") public SkillRegistry.View view(@PathVariable String id, @PathVariable String version) {
        try { return registry.view(id, version); }
        catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "SKILL_NOT_FOUND"); }
    }
}
