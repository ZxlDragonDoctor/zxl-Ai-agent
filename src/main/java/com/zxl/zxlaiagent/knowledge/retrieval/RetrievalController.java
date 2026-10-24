package com.zxl.zxlaiagent.knowledge.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@Profile("lesson12-demo")
@RequestMapping("/ai/learning/knowledge/retrieval")
public final class RetrievalController {
    private final RetrievalService service;
    public RetrievalController(RetrievalService service) { this.service=service; }
    @PostMapping public RetrievalService.Response retrieve(@RequestBody JsonNode body) { return service.retrieve(parse(body)); }
    public static RetrievalService.Request parse(JsonNode body) {
        Set<String> allowed=Set.of("query","mode","topK","candidateLimit","rerank","rewrite","budgetMs");
        if(!body.isObject())throw new IllegalArgumentException("A retrieval object is required");
        body.fieldNames().forEachRemaining(name->{if(!allowed.contains(name))throw new IllegalArgumentException("Unknown retrieval option: "+name);});
        for(String name:List.of("query","mode"))if(body.has(name)&&!body.get(name).isTextual())throw new IllegalArgumentException(name+" must be a string");
        for(String name:List.of("topK","candidateLimit","budgetMs"))if(body.has(name)&&(!body.get(name).isIntegralNumber()||!body.get(name).canConvertToInt()))throw new IllegalArgumentException(name+" must be an integer");
        for(String name:List.of("rerank","rewrite"))if(body.has(name)&&!body.get(name).isBoolean())throw new IllegalArgumentException(name+" must be a boolean");
        var request=new RetrievalService.Request(body.path("query").asText(""),body.path("mode").asText("hybrid"),body.path("topK").asInt(5),body.path("candidateLimit").asInt(20),body.path("rerank").asBoolean(false),body.path("rewrite").asBoolean(false),body.path("budgetMs").asLong(10000));
        RetrievalService.validate(request); return request;
    }
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<Map<String,String>> bad(IllegalArgumentException failure) {
        return ResponseEntity.badRequest().body(Map.of("code","INVALID_RETRIEVAL_OPTIONS","message",failure.getMessage()));
    }
    @ExceptionHandler(IllegalStateException.class) public ResponseEntity<Map<String,String>> failed(IllegalStateException failure) {
        String code=failure instanceof RetrievalService.Failure f?f.code():failure instanceof RetrievalDeadline.Expired?"RETRIEVAL_DEADLINE_EXCEEDED":failure instanceof RetrievalDeadline.Cancelled?"RETRIEVAL_CANCELLED":"RETRIEVAL_FAILED";
        return ResponseEntity.status(503).body(Map.of("code",code,"message","检索未完成，请检查数据库、模型服务或请求预算后重试。"));
    }
}
