package com.zxl.zxlaiagent.knowledge.scoped;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.zxl.zxlaiagent.knowledge.*;
import com.zxl.zxlaiagent.knowledge.answer.*;
import com.zxl.zxlaiagent.knowledge.retrieval.*;
import com.zxl.zxlaiagent.security.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.Duration;
import java.util.*;
/** A deliberately narrow MVC dispatch surface; no evaluation or legacy profile resolution. */
@RestController
@org.springframework.context.annotation.Profile("lesson17-worker")
@RequestMapping(WorkerRequestContract.BASE)
public final class ScopedKnowledgeController {
 private final ScopedKnowledgeFactory factory;private final ObjectMapper json=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
 public ScopedKnowledgeController(ScopedKnowledgeFactory factory){this.factory=factory;}
 @RequestMapping(value={"/status","/documents","/documents/{id}","/search","/retrieval","/answers","/memories","/memories/recall","/memories/proposals","/memories/{id}","/memories/{id}/confirm","/memories/{id}/reject"})
 public Object handle(HttpServletRequest request)throws java.io.IOException{
  ActorRef actor=WorkerAuthenticationFilter.actor(request);String path=request.getRequestURI().substring(WorkerRequestContract.BASE.length()),method=request.getMethod();WorkerRequestContract.action(method,request.getRequestURI());
  if(method.equals("GET")){
   if(path.equals("/status")){factory.knowledge(actor).list();return Map.of("storage","PostgreSQL + pgvector","available",true,"scope","authenticated");}
   if(path.equals("/documents"))return factory.knowledge(actor).list();
   if(path.startsWith("/documents/"))return factory.knowledge(actor).detail(path.substring(11));
   if(path.equals("/search"))return factory.knowledge(actor).search(request.getParameter("q"),number(request,"limit",5));
   if(path.equals("/memories"))return factory.memory(actor).list();
   if(path.equals("/memories/recall"))return factory.memory(actor).recall(number(request,"maxChars",1000),Duration.ofSeconds(2));
  }
  if(method.equals("DELETE")){
   if(path.startsWith("/documents/"))return factory.knowledge(actor).delete(path.substring(11));
   return factory.memory(actor).delete(path.substring(10),Long.parseLong(Objects.requireNonNull(request.getParameter("version"))));
  }
  byte[] body=request.getInputStream().readAllBytes();
  if(path.equals("/documents")){var input=parse(body,DocumentInput.class);return factory.knowledge(actor).importDocument(input.documentId(),input.sourceName(),input.content());}
  if(path.equals("/retrieval"))return factory.retrieval(actor).retrieve(parse(body,RetrievalService.Request.class));
  if(path.equals("/answers")){var input=parse(body,AnswerInput.class);return factory.answer(actor).answer(new AnswerService.Request(input.question(),input.conversationId(),input.mode(),input.budgetMs(),null));}
  if(path.equals("/memories/proposals")){var input=parse(body,MemoryInput.class);return factory.memory(actor).propose(input.sessionId(),input.messageId(),input.userText(),input.ttlSeconds());}
  String[] split=path.split("/");String id=split[2];
  if(method.equals("PATCH")){var input=parse(body,CorrectionInput.class);return factory.memory(actor).correct(id,input.version(),input.sessionId(),input.messageId(),input.userText(),input.ttlSeconds());}
  var input=parse(body,VersionInput.class);return path.endsWith("/confirm")?factory.memory(actor).confirm(id,input.version()):factory.memory(actor).reject(id,input.version());
 }
 public record DocumentInput(String documentId,String sourceName,String content){}
 public record AnswerInput(String question,String conversationId,String mode,long budgetMs){}
 public record MemoryInput(String sessionId,String messageId,String userText,int ttlSeconds){}
 public record CorrectionInput(long version,String sessionId,String messageId,String userText,int ttlSeconds){}
 public record VersionInput(long version){}
 private <T>T parse(byte[] body,Class<T> type){try{return json.readValue(body,type);}catch(java.io.IOException e){throw new IllegalArgumentException("Invalid strict JSON");}}
 private int number(HttpServletRequest request,String name,int defaultValue){String value=request.getParameter(name);return value==null?defaultValue:Integer.parseInt(value);}
 @ExceptionHandler(SecurityFailure.class) public ResponseEntity<?> denied(SecurityFailure failure){return ResponseEntity.status(failure.status()).body(Map.of("code",failure.code(),"message",failure.code()));}
 @ExceptionHandler({IllegalArgumentException.class,NullPointerException.class}) public ResponseEntity<?> invalid(Exception failure){return ResponseEntity.badRequest().body(Map.of("code","INVALID_INPUT","message","Invalid request"));}
 @ExceptionHandler(NoSuchElementException.class) public ResponseEntity<?> missing(Exception failure){return ResponseEntity.status(404).body(Map.of("code","NOT_FOUND","message","Not found"));}
 @ExceptionHandler({KnowledgeService.Conflict.class,com.zxl.zxlaiagent.knowledge.memory.MemoryService.Conflict.class}) public ResponseEntity<?> conflict(Exception failure){return ResponseEntity.status(409).body(Map.of("code","CONFLICT","message","Refresh and retry"));}
 @ExceptionHandler(IllegalStateException.class) public ResponseEntity<?> unavailable(Exception failure){return ResponseEntity.status(503).body(Map.of("code","SERVICE_UNAVAILABLE","message","Service unavailable"));}
}
