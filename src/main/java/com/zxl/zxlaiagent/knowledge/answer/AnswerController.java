package com.zxl.zxlaiagent.knowledge.answer;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.util.*;
@RestController
@Profile("lesson12-demo")
@RequestMapping("/ai/learning/knowledge/answers")
public final class AnswerController {
 private final AnswerService service;
 public AnswerController(AnswerService service){this.service=service;}
 @PostMapping public ResponseEntity<AnswerService.Response> answer(@RequestBody JsonNode body){
  Set<String> allowed=Set.of("question","conversationId","mode","budgetMs","profile");
  if(!body.isObject())throw new IllegalArgumentException("JSON object required");body.fieldNames().forEachRemaining(k->{if(!allowed.contains(k))throw new IllegalArgumentException("Unknown field");});
  for(String name:List.of("question","conversationId","mode"))if(!body.path(name).isTextual())throw new IllegalArgumentException("String field required");
  if(!body.path("budgetMs").isIntegralNumber()||!body.path("budgetMs").canConvertToLong()||body.has("profile")&&!body.path("profile").isTextual())throw new IllegalArgumentException("Invalid type");
  var response=service.answer(new AnswerService.Request(body.path("question").textValue(),body.path("conversationId").textValue(),body.path("mode").textValue(),body.path("budgetMs").longValue(),body.has("profile")?body.path("profile").textValue():"alice"));
  return ResponseEntity.status("SERVICE_FAILURE".equals(response.status())?503:200).body(response);
 }
 @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<Map<String,String>> invalid(){return ResponseEntity.badRequest().body(Map.of("code","INVALID_ANSWER_REQUEST","message","问题、会话、模式、预算或演示用户不合法。"));}
}
