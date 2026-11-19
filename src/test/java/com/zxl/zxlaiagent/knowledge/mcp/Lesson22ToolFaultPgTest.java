package com.zxl.zxlaiagent.knowledge.mcp;

import com.zxl.zxlaiagent.security.*;
import com.zxl.zxlaiagent.security.execution.OperationBoundary;
import com.zxl.zxlaiagent.research.ResearchRunner;
import com.zxl.zxlaiagent.agent.model.AgentExecutionEvent;
import com.zxl.zxlaiagent.tools.support.*;
import io.modelcontextprotocol.spec.McpSchema;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

/** Controlled dependency faults, real PG authority and real SDK SSE. No live LLM or orchestration report. */
class Lesson22ToolFaultPgTest {
 private static final String TOKEN="synthetic-lesson22-internal-service-token";
 private SecurityDatabase db; private ActorRef actor; private String tenant;
 @BeforeEach void prepare(){db=SecurityDatabase.fromEnvironment();tenant="lesson22-fault-"+UUID.randomUUID();var auth=new AuthenticationService(db);auth.provision(tenant,tenant,"synthetic-password",tenant,"employee");actor=auth.login(tenant,"synthetic-password",tenant).actor().ref();}
 @AfterEach void cleanup(){if(db!=null){db.tx(c->{try(var s=SecurityDatabase.statement(c,"DELETE FROM tenant WHERE tenant_id=?",tenant)){s.executeUpdate();}try(var s=SecurityDatabase.statement(c,"DELETE FROM subject WHERE subject_id=?",tenant)){s.executeUpdate();}return null;});db.close();}}
 private int writes(){return db.tx(c->{try(var s=SecurityDatabase.statement(c,"SELECT count(*) FROM approved_ticket WHERE tenant_id=?",tenant);var r=s.executeQuery()){r.next();return r.getInt(1);}});}
 @Test void malformedTypedReplyIsRejectedAtAdapterAfterRealSseCall()throws Exception{exercise("tool-failure-2","malformed-reply",false);}
 @Test void discoveredTransportCanDisappearBeforeDispatch()throws Exception{exercise("tool-failure-3","mcp-unavailable",false);}
 @Test void oversizedLookupResponseIsRejected()throws Exception{exercise("tool-failure-4","response-limit",false);}
 @Test void enteredWorkerTimeoutIsReportedAsDeadline()throws Exception{exercise("tool-failure-5","retrieval-timeout",true);}
 private void exercise(String id,String scenario,boolean timeout)throws Exception{
  var policy=new AuthorizationService(db);var tickets=new ServiceCallAuthorizer(db,policy);var entered=new AtomicInteger();
  var bytes=new ObjectMapper().readTree(Files.readString(Path.of("docs/learning/examples/lesson22-cases.json")));
  String prompt=null;for(var item:bytes.path("cases"))if(item.path("id").asText().equals(id))prompt=item.path("input").path("prompt").asText();assertNotNull(prompt);
  var events=new ArrayList<AgentExecutionEvent>();int before=writes();
  try(var server=FormalKnowledgeMcpServer.start(0,new FormalKnowledgeMcpTool(tickets,policy,(a,r)->{
   entered.incrementAndGet();if(timeout){try{new java.util.concurrent.CountDownLatch(1).await(5,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException stopped){Thread.currentThread().interrupt();}}
   return Map.of("hits",List.of(),"payload",scenario.equals("response-limit")?"x".repeat(140000):"fixture");
  }),TOKEN);var client=new FormalKnowledgeMcpClient(URI.create("http://127.0.0.1:"+server.port()),TOKEN,tickets,policy)){
   var gateway=new ResearchRunner.Gateway(){
    public List<ResearchRunner.Tool> discover(Duration budget,BooleanSupplier cancelled){var found=client.discover(budget,cancelled);assertEquals(1,found.size());var result=found.stream().map(t->new ResearchRunner.Tool(t.exposedName(),t.definition().description(),ToolResults.json(t.definition().inputSchema()))).toList();if(scenario.equals("mcp-unavailable"))server.close();return result;}
    public String call(String name,String input,Duration budget,BooleanSupplier cancelled){
     var result=client.call(actor,name,StructuredMcpToolCallback.validateInput(input,FormalKnowledgeMcpProtocol.modelDefinition()),timeout?Duration.ofMillis(1200):budget,cancelled);
     // Deliberate typed-response fault at the SDK-to-adapter boundary, not malformed wire JSON.
     if(scenario.equals("malformed-reply"))result=null;
     return StructuredMcpToolCallback.views(StructuredMcpToolCallback.adaptResult(result)).original();
    }
   };
   var model=new ResearchRunner.Model(){
    public ResearchRunner.Selection select(String q,List<ResearchRunner.Tool> tools,Duration b,BooleanSupplier cancelled){return new ResearchRunner.Selection("fixture-call",tools.get(0).name(),ToolResults.json(Map.of("query",q)));}
    public String answer(String q,List<JsonNode> hits,Duration b,BooleanSupplier cancelled){fail("Fault must not reach answer generation");return null;}
   };
   var runner=ResearchRunner.authorized(gateway,model,(hits,b,c)->{fail("Fault must not reach verifier");return hits;},OperationBoundary.unrestricted());
   long start=System.nanoTime();var failure=assertThrows(RuntimeException.class,()->runner.run(promptFinal(id,bytes),Duration.ofSeconds(25),()->false,events::add));
   assertTrue(events.stream().anyMatch(e->e.type().equals("TOOL_STARTED")&&e.toolName().equals(FormalKnowledgeMcpProtocol.EXPOSED_NAME)));
   var failed=events.stream().filter(e->e.type().equals("TOOL_FAILED")).findFirst().orElseThrow();var body=ToolResults.read(failed.content());String code=body.path("code").asText();if(code.equals("MCP_TOOL_ERROR"))code=body.path("data").path("remoteCode").asText();
   if(scenario.equals("mcp-unavailable")){assertEquals(0,entered.get());assertTrue(Set.of("MCP_NETWORK_FAILURE","MCP_PROTOCOL_FAILURE","MCP_DEADLINE_EXCEEDED").contains(code),code);}else{assertEquals(1,entered.get());assertEquals(timeout?"MCP_DEADLINE_EXCEEDED":"MCP_INVALID_RESPONSE",code);}
   int after=writes();assertEquals(0,before);assertEquals(0,after);
   var observation=new LinkedHashMap<String,Object>();observation.put("id",id);observation.put("faultScenario",scenario);observation.put("model","controlled-java-fixture");observation.put("scope","ResearchRunner + PG authority + SDK SSE; no Orchestrator report");observation.put("typedAdapterInjection",scenario.equals("malformed-reply"));observation.put("enteredLookups",entered.get());observation.put("beforeWrites",before);observation.put("afterWrites",after);observation.put("elapsedMs",(System.nanoTime()-start)/1000000);observation.put("exceptionType",failure.getClass().getSimpleName());observation.put("terminalCode",failure instanceof FormalKnowledgeMcpClient.Failure typed?typed.code():failure.getMessage());observation.put("discovered",true);observation.put("faultTriggered",scenario.equals("mcp-unavailable")||entered.get()==1);observation.put("toolError",code);observation.put("events",events);
   Files.writeString(Path.of("target/lesson22-"+id+"-observation.json"),new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(observation));
  }
 }
 private static String promptFinal(String id,JsonNode bytes){for(var item:bytes.path("cases"))if(item.path("id").asText().equals(id))return item.path("input").path("prompt").asText();throw new IllegalArgumentException();}
}


