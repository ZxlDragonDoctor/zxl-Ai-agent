package com.zxl.zxlaiagent.knowledge.evaluation;

import org.springframework.http.ResponseEntity;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@Profile("lesson12-demo")
@RequestMapping("/ai/learning/knowledge/evaluation")
public final class EvaluationController {
    private final EvaluationService service;
    public EvaluationController(EvaluationService service){this.service=service;}
    public record Seed(Boolean real){}
    @GetMapping("/status") public EvaluationService.Status status(){return service.status();}
    @PostMapping("/seed") public EvaluationService.Status seed(@RequestBody(required=false) Seed request){return service.seed(request!=null&&Boolean.TRUE.equals(request.real()));}
    @PostMapping("/run") public EvaluationService.Report run(@RequestBody(required=false) EvaluationService.Input request){return service.run(request);}
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<Map<String,String>> bad(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("code","EVALUATION_INVALID","message",e.getMessage()));}
    @ExceptionHandler(IllegalStateException.class) public ResponseEntity<Map<String,String>> unavailable(IllegalStateException e){return ResponseEntity.status(503).body(Map.of("code","EVALUATION_UNAVAILABLE","message","评测当前不可用，请检查数据库连接、模型配置或并行评测状态；没有回退内存。"));}
}
