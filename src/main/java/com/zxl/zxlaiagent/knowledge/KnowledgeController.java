package com.zxl.zxlaiagent.knowledge;

import org.springframework.http.ResponseEntity;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** Local teaching endpoint: fixed demo tenant is not authentication or production authorization. */
@RestController
@Profile("lesson12-demo")
@RequestMapping("/ai/learning/knowledge")
public class KnowledgeController {
    private final KnowledgeService service;
    public KnowledgeController(KnowledgeService service){this.service=service;}
    public record Input(String documentId,String sourceName,String content){}
    @GetMapping("/status") public Map<String,Object> status(){return service.status();}
    @GetMapping("/documents") public List<KnowledgeService.Manifest> list(){return service.list();}
    @GetMapping("/documents/{id}") public KnowledgeService.Detail detail(@PathVariable String id){return service.detail(id);}
    @PostMapping("/documents") public KnowledgeService.ImportResult add(@RequestBody Input input){return service.importDocument(input.documentId(),input.sourceName(),input.content());}
    @DeleteMapping("/documents/{id}") public KnowledgeService.Manifest delete(@PathVariable String id){return service.delete(id);}
    @GetMapping("/search") public KnowledgeService.Search search(@RequestParam String q,@RequestParam(defaultValue="5") int limit){return service.search(q,limit);}
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<Map<String,String>> bad(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("code","INVALID_INPUT","message",e.getMessage()));}
    @ExceptionHandler(NoSuchElementException.class) public ResponseEntity<Map<String,String>> missing(NoSuchElementException e){return ResponseEntity.status(404).body(Map.of("code","NOT_FOUND","message",e.getMessage()));}
    @ExceptionHandler(KnowledgeService.Conflict.class) public ResponseEntity<Map<String,String>> conflict(KnowledgeService.Conflict e){return ResponseEntity.status(409).body(Map.of("code","SUPERSEDED","message",e.getMessage()));}
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String,String>> failed(IllegalStateException failure){
        // A connection failure can affect reads, health checks and commit acknowledgements.
        // Never infer that an earlier version is readable merely from a thrown exception.
        Throwable cause=failure;
        for(int depth=0;cause!=null&&depth<16;depth++,cause=cause.getCause()){
            if(cause instanceof java.sql.SQLException sql){
                String state=sql.getSQLState();
                if(state!=null&&(state.startsWith("08")||Set.of("57P01","57P02","57P03").contains(state))){
                    return ResponseEntity.status(503).body(Map.of("code","STORAGE_UNAVAILABLE","message","数据库暂不可用，当前资料状态无法确认；请恢复连接后重试。"));
                }
            }
        }
        return ResponseEntity.internalServerError().body(Map.of("code","OPERATION_FAILED","message","知识库操作失败；请查看服务日志，并在服务恢复后重试。"));
    }
}
