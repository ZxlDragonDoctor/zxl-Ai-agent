package com.zxl.zxlaiagent.knowledge.mcp;
import com.zxl.zxlaiagent.security.*;
import com.zxl.zxlaiagent.security.execution.OperationBoundary;
import com.zxl.zxlaiagent.research.ResearchRunner;
import com.zxl.zxlaiagent.agent.model.AgentExecutionEvent;
import com.zxl.zxlaiagent.tools.support.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import java.nio.file.*;import java.net.URI;import java.time.Duration;import java.util.*;import java.util.concurrent.*;import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;
/** Real PG authority + SSE + production ResearchRunner; deterministic model, no orchestrator report. */
class Lesson22RemainingPgTest {
 static final String TOKEN="synthetic-lesson22-internal-service-token";
 SecurityDatabase db;ActorRef actor;String tenant;
 @BeforeEach void setup(){db=SecurityDatabase.fromEnvironment();tenant="lesson22-remaining-"+UUID.randomUUID();var auth=new AuthenticationService(db);auth.provision(tenant,tenant,"synthetic-password",tenant,"employee");actor=auth.login(tenant,"synthetic-password",tenant).actor().ref();}
 @AfterEach void close(){if(db!=null){db.tx(c->{try(var s=SecurityDatabase.statement(c,"DELETE FROM tenant WHERE tenant_id=?",tenant)){s.executeUpdate();}try(var s=SecurityDatabase.statement(c,"DELETE FROM subject WHERE subject_id=?",tenant)){s.executeUpdate();}return null;});db.close();}}
 int writes(){return db.tx(c->{try(var s=SecurityDatabase.statement(c,"SELECT count(*) FROM approved_ticket WHERE tenant_id=?",tenant);var r=s.executeQuery()){r.next();return r.getInt(1);}});}
 ResearchRunner runner(FormalKnowledgeMcpClient client){return ResearchRunner.authorized(new ResearchRunner.Gateway(){
 public List<ResearchRunner.Tool> discover(Duration b,BooleanSupplier c){return client.discover(b,c).stream().map(t->new ResearchRunner.Tool(t.exposedName(),t.definition().description(),ToolResults.json(t.definition().inputSchema()))).toList();}
 public String call(String n,String input,Duration b,BooleanSupplier c){return StructuredMcpToolCallback.adaptResult(client.call(actor,n,StructuredMcpToolCallback.validateInput(input,FormalKnowledgeMcpProtocol.modelDefinition()),b,c));}
 },new ResearchRunner.Model(){public ResearchRunner.Selection select(String q,List<ResearchRunner.Tool> t,Duration b,BooleanSupplier c){return new ResearchRunner.Selection("fixture-call",t.get(0).name(),ToolResults.json(Map.of("query",q)));}public String answer(String q,List<JsonNode> h,Duration b,BooleanSupplier c){fail("must not answer");return null;}},(h,b,c)->{fail("must not verify");return h;},OperationBoundary.unrestricted());}
 @Test void malformedBusinessJsonTravelsAcrossActualSse()throws Exception{
 var policy=new AuthorizationService(db);var tickets=new ServiceCallAuthorizer(db,policy);var entered=new java.util.concurrent.atomic.AtomicInteger();var events=new ArrayList<AgentExecutionEvent>();int before=writes();
 try(var server=MalformedKnowledgeMcpFixture.start(0,new FormalKnowledgeMcpTool(tickets,policy,(a,r)->{entered.incrementAndGet();return Map.of("hits",List.of());}),TOKEN);var client=new FormalKnowledgeMcpClient(URI.create("http://127.0.0.1:"+server.port()),TOKEN,tickets,policy)){
 client.discover(Duration.ofSeconds(5),()->false);long start=System.nanoTime();var failure=assertThrows(IllegalStateException.class,()->runner(client).run("Find the visitor badge policy.",Duration.ofSeconds(10),()->false,events::add));assertEquals("RESEARCH_REMOTE_FAILURE",failure.getMessage());assertEquals(1,entered.get());assertTrue(events.stream().anyMatch(e->e.type().equals("TOOL_STARTED")));var failed=events.stream().filter(e->e.type().equals("TOOL_FAILED")).findFirst().orElseThrow();assertEquals("MCP_INVALID_RESPONSE",ToolResults.read(failed.content()).path("code").asText());int after=writes();assertEquals(before,after);
 var o=new LinkedHashMap<String,Object>();o.put("id","tool-failure-2");o.put("faultScenario","malformed-reply");o.put("model","controlled-java-fixture");o.put("typedAdapterInjection",false);o.put("wireFault","TextContent malformed business JSON transported over actual SSE");o.put("enteredLookups",entered.get());o.put("countsScope","fixture-tenant");o.put("beforeWrites",before);o.put("afterWrites",after);o.put("elapsedMs",(System.nanoTime()-start)/1000000);o.put("terminalCode",failure.getMessage());o.put("discovered",true);o.put("faultTriggered",true);o.put("toolError","MCP_INVALID_RESPONSE");o.put("events",events);new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of("target/lesson22-tool-failure-2-wire-observation.json").toFile(),o);
 }}
 // Delay the actual RPC result: an early remote tool timeout must remain a child timeout,
 // while this test specifically proves expiry of the caller total budget before result delivery.
 @Test void totalBudgetExpiresOnlyAfterRealToolLookupEntered()throws Exception{
 var policy=new AuthorizationService(db);var tickets=new ServiceCallAuthorizer(db,policy);var entered=new CountDownLatch(1);var exited=new CountDownLatch(1);var events=new ArrayList<AgentExecutionEvent>();int before=writes();
 try(var server=DelayedKnowledgeMcpFixture.start(0,new FormalKnowledgeMcpTool(tickets,policy,(a,r)->{entered.countDown();try{new CountDownLatch(1).await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}finally{exited.countDown();}return Map.of("hits",List.of());}),TOKEN);var client=new FormalKnowledgeMcpClient(URI.create("http://127.0.0.1:"+server.port()),TOKEN,tickets,policy)){
 client.discover(Duration.ofSeconds(5),()->false);long start=System.nanoTime();var failure=assertThrows(IllegalStateException.class,()->runner(client).run("Find the parking permit process.",Duration.ofMillis(1000),()->false,events::add));assertEquals("RESEARCH_DEADLINE",failure.getMessage());assertEquals(0,entered.getCount());assertTrue(events.stream().anyMatch(e->e.type().equals("TOOL_STARTED")));assertTrue(exited.await(3,TimeUnit.SECONDS));assertFalse(events.stream().anyMatch(e->e.type().equals("TOOL_COMPLETED")));int after=writes();assertEquals(before,after);
 var o=new LinkedHashMap<String,Object>();o.put("id","budget-2");o.put("lookupEntered",entered.getCount()==0);o.put("reachedPhase","tool-wait");o.put("phaseConfirmed",true);o.put("clockMode","system-monotonic");o.put("model","controlled-java-fixture");o.put("scope","ResearchRunner total deadline + PG authority + actual SSE; no Orchestrator report");o.put("countsScope","fixture-tenant");o.put("beforeWrites",before);o.put("afterWrites",after);o.put("errorCode",failure.getMessage());o.put("outcomeStatus","failed");o.put("cleanupConfirmed",exited.getCount()==0);o.put("activeTasks",exited.getCount());o.put("elapsedMs",(System.nanoTime()-start)/1000000);o.put("events",events);new ObjectMapper().writeValue(Path.of("target/lesson22-budget-2-controlled.json").toFile(),o);
 }}
}
