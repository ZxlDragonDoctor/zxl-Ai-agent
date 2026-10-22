package com.zxl.zxlaiagent.controller;

import com.zxl.zxlaiagent.persistence.*;
import com.zxl.zxlaiagent.ticket.TicketCommand;
import com.zxl.zxlaiagent.multiagent.runtime.retry.RetryFailure;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** Explicit, unauthenticated local learning profile; not an enterprise write-tool endpoint. */
@RestController
@Profile("lesson9-demo")
@RequestMapping("/ai/learning/persistent-runs")
public class LearningPersistenceController {
    private final PersistentWorkflowRepository repository;
    private final PersistentWorker worker;
    public LearningPersistenceController(PersistentWorkflowRepository repository,PersistentWorker worker){this.repository=repository;this.worker=worker;}
    public record CreateRequest(String operationId,String title,String description,Long timeoutMs) {}
    @PostMapping public PersistentModels.Run create(@RequestBody CreateRequest request) {
        return repository.create(new TicketCommand(request.operationId(),request.title(),request.description()),request.timeoutMs()==null?60_000:request.timeoutMs());
    }
    @GetMapping("/{id}") public PersistentModels.Snapshot get(@PathVariable String id){return repository.get(id);}
    @PostMapping("/{id}/cancel") public PersistentModels.Run cancel(@PathVariable String id){return repository.cancel(id);}
    @PostMapping("/recover") public Map<String,Integer> recover(){return Map.of("queued",worker.scan());}
    @ExceptionHandler(PersistentModels.Conflict.class) public ResponseEntity<?> conflict(PersistentModels.Conflict e) {
        return ResponseEntity.status(e.code.equals("RUN_NOT_FOUND")?404:409).body(Map.of("code",e.code));
    }
    @ExceptionHandler({IllegalArgumentException.class,RetryFailure.class}) public ResponseEntity<?> invalid(RuntimeException e) {
        return ResponseEntity.badRequest().body(Map.of("code","INVALID_REQUEST","message",e.getMessage()));
    }
}
