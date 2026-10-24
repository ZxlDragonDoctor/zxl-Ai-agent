package com.zxl.zxlaiagent.knowledge.memory;
import com.fasterxml.jackson.databind.JsonNode;
import com.zxl.zxlaiagent.knowledge.KnowledgeDatabase;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.*;
import java.util.*;
/** Only registered local demo identities; these endpoints do not implement production authentication. */
@RestController @Profile("lesson12-demo") @RequestMapping("/ai/learning/knowledge/memories")
public final class MemoryController {
 private final KnowledgeDatabase db;private final Clock clock;private final java.util.function.UnaryOperator<MemoryService.Scope> trustedScopeMapper;
 @org.springframework.beans.factory.annotation.Autowired public MemoryController(KnowledgeDatabase db){this(db,Clock.systemUTC());}
 public MemoryController(KnowledgeDatabase db,Clock clock){this(db,clock,java.util.function.UnaryOperator.identity());}
 MemoryController(KnowledgeDatabase db,Clock clock,java.util.function.UnaryOperator<MemoryService.Scope> trustedScopeMapper){this.db=db;this.clock=clock;this.trustedScopeMapper=trustedScopeMapper;}
 private MemoryService service(String profile){return new MemoryService(db,trustedScopeMapper.apply(MemoryService.demoScope(profile)),clock);}
 @GetMapping public List<MemoryService.Memory> list(@RequestParam(defaultValue="alice") String profile){return service(profile).list();}
 @GetMapping("/recall") public MemoryService.Recall recall(@RequestParam(defaultValue="alice")String profile,@RequestParam(defaultValue="1000")int maxChars){return service(profile).recall(maxChars,Duration.ofSeconds(2));}
 @PostMapping("/proposals") public MemoryService.Memory propose(@RequestBody JsonNode body){fields(body,"profile","sessionId","messageId","userText","ttlSeconds");return service(profile(body)).propose(text(body,"sessionId"),text(body,"messageId"),text(body,"userText"),integer(body,"ttlSeconds"));}
 @PostMapping("/{id}/confirm") public MemoryService.Memory confirm(@PathVariable String id,@RequestBody JsonNode body){fields(body,"profile","version");return service(profile(body)).confirm(id,number(body,"version"));}
 @PostMapping("/{id}/reject") public MemoryService.Memory reject(@PathVariable String id,@RequestBody JsonNode body){fields(body,"profile","version");return service(profile(body)).reject(id,number(body,"version"));}
 @PatchMapping("/{id}") public MemoryService.Memory correct(@PathVariable String id,@RequestBody JsonNode body){fields(body,"profile","version","sessionId","messageId","userText","ttlSeconds");return service(profile(body)).correct(id,number(body,"version"),text(body,"sessionId"),text(body,"messageId"),text(body,"userText"),integer(body,"ttlSeconds"));}
 @DeleteMapping("/{id}") public MemoryService.Memory delete(@PathVariable String id,@RequestParam(defaultValue="alice")String profile,@RequestParam long version){return service(profile).delete(id,version);}
 private static String profile(JsonNode b){return b.has("profile")?text(b,"profile"):"alice";}
 private static void fields(JsonNode body,String... allowed){if(body==null||!body.isObject())throw new IllegalArgumentException("JSON object required");var names=Set.of(allowed);body.fieldNames().forEachRemaining(k->{if(!names.contains(k))throw new IllegalArgumentException("Unknown field: "+k);});}
 private static String text(JsonNode b,String key){var n=b.get(key);if(n==null||!n.isTextual()||n.textValue().length()>200)throw new IllegalArgumentException("Invalid "+key);return n.textValue();}
 private static long number(JsonNode b,String key){var n=b.get(key);if(n==null||!n.isIntegralNumber()||!n.canConvertToLong())throw new IllegalArgumentException("Invalid "+key);return n.longValue();}
 private static int integer(JsonNode b,String key){long x=number(b,key);if(x>Integer.MAX_VALUE||x<Integer.MIN_VALUE)throw new IllegalArgumentException("Invalid "+key);return (int)x;}
 @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<Map<String,String>> invalid(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("code","INVALID_MEMORY_INPUT","message",e.getMessage()));}
 @ExceptionHandler(NoSuchElementException.class) public ResponseEntity<Map<String,String>> missing(NoSuchElementException e){return ResponseEntity.status(404).body(Map.of("code","MEMORY_NOT_FOUND","message","记忆不存在"));}
 @ExceptionHandler(MemoryService.Conflict.class) public ResponseEntity<Map<String,String>> conflict(MemoryService.Conflict e){return ResponseEntity.status(409).body(Map.of("code","MEMORY_CONFLICT","message",e.getMessage()));}
 @ExceptionHandler(IllegalStateException.class) public ResponseEntity<Map<String,String>> unavailable(IllegalStateException e){return ResponseEntity.status(503).body(Map.of("code","MEMORY_STORAGE_UNAVAILABLE","message","记忆数据库暂不可用；未回退内存"));}
}
