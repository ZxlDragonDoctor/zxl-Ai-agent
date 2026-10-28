package com.zxl.zxlaiagent.knowledge.mcp;

import com.zxl.zxlaiagent.security.*;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.*;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Real PG tickets/decisions + actual SDK SSE; synthetic scoped retrieval does not prove PG knowledge SQL. */
class FormalKnowledgeMcpPgTest {
 private SecurityDatabase db;private AuthenticationService auth;private AuthorizationService policy;private ServiceCallAuthorizer tickets;
 private ActorRef alice,bob;private final List<String> subjects=new ArrayList<>();private final List<String> tenants=new ArrayList<>();
 @BeforeEach void setup(){db=SecurityDatabase.fromEnvironment();auth=new AuthenticationService(db);policy=new AuthorizationService(db);tickets=new ServiceCallAuthorizer(db,policy);alice=actor();bob=actor();}
 private ActorRef actor(){String id="mcp-test-"+UUID.randomUUID();String tenant=id+"-tenant";subjects.add(id);tenants.add(tenant);auth.provision(id,id,"synthetic-password",tenant,"employee");return auth.login(id,"synthetic-password",tenant).actor().ref();}
 @AfterEach void cleanup(){if(db!=null)db.tx(c->{for(String subject:subjects){try(var s=SecurityDatabase.statement(c,"DELETE FROM authorization_audit WHERE subject_id=?",subject)){s.executeUpdate();}try(var s=SecurityDatabase.statement(c,"DELETE FROM subject WHERE subject_id=?",subject)){s.executeUpdate();}}for(String tenant:tenants)try(var s=SecurityDatabase.statement(c,"DELETE FROM tenant WHERE tenant_id=?",tenant)){s.executeUpdate();}return null;});}
 private Map<String,Object> business(){return new HashMap<>(Map.of("query","same-query","topK",1,"mode","keyword","budgetMs",3000));}
 private Map<String,Object> wire(ActorRef actor){var args=business();args.put("authorization",tickets.issue(actor,FormalKnowledgeMcpProtocol.AUDIENCE,"knowledge.read",null,FormalKnowledgeMcpProtocol.requestHash(args)));return args;}
 private static String text(McpSchema.CallToolResult result){return ((McpSchema.TextContent)result.content().get(0)).text();}
 private McpSchema.CallToolResult scopedCall(FormalKnowledgeMcpClient client,ActorRef actor,List<AuthorizationService.Permission> extra,String runId)throws Exception{
  try{return (McpSchema.CallToolResult)client.getClass().getMethod("call",ActorRef.class,String.class,Map.class,Duration.class,java.util.function.BooleanSupplier.class,List.class,String.class)
    .invoke(client,actor,FormalKnowledgeMcpProtocol.EXPOSED_NAME,Map.of("query","same-query"),Duration.ofSeconds(5),(java.util.function.BooleanSupplier)()->false,extra,runId);}
  catch(java.lang.reflect.InvocationTargetException failed){if(failed.getCause() instanceof RuntimeException runtime)throw runtime;throw failed;}
 }
 @Test void additionalOwnershipIsRecheckedBeforeToolDispatch()throws Exception{
  String token="synthetic-mcp-internal-token-at-least-32-characters",other=UUID.randomUUID().toString();var calls=new AtomicInteger();
  policy.registerResource(bob,new ResourceRef("conversation",other));
  try(var server=FormalKnowledgeMcpServer.start(0,new FormalKnowledgeMcpTool(tickets,policy,(actor,r)->{calls.incrementAndGet();return Map.of();}),token);
      var client=new FormalKnowledgeMcpClient(URI.create("http://127.0.0.1:"+server.port()),token,tickets,policy)){
   client.discover(Duration.ofSeconds(5),()->false);
   var denied=assertThrows(FormalKnowledgeMcpClient.Failure.class,()->scopedCall(client,alice,List.of(new AuthorizationService.Permission("conversation.readOwn",new ResourceRef("conversation",other))),null));
   assertEquals("MCP_NOT_AUTHORIZED",denied.code());assertEquals(0,calls.get());
  }
 }
 @Test void actualDispatchAuditsCombinedPermissionsAndRemoteWaitingHoldsNoPgLock()throws Exception{
  String token="synthetic-mcp-internal-token-at-least-32-characters",conversation=UUID.randomUUID().toString(),run=UUID.randomUUID().toString();
  policy.registerResource(alice,new ResourceRef("conversation",conversation));policy.registerResource(alice,new ResourceRef("run",run));
  var permissions=List.of(new AuthorizationService.Permission("agent.execute",null),new AuthorizationService.Permission("conversation.readOwn",new ResourceRef("conversation",conversation)),new AuthorizationService.Permission("run.readOwn",new ResourceRef("run",run)));
  var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
  try(var server=FormalKnowledgeMcpServer.start(0,new FormalKnowledgeMcpTool(tickets,policy,(actor,r)->{entered.countDown();try{release.await(8,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}return Map.of("private","discard");}),token);
      var client=new FormalKnowledgeMcpClient(URI.create("http://127.0.0.1:"+server.port()),token,tickets,policy)){
   try{
    client.discover(Duration.ofSeconds(5),()->false);var pending=pool.submit(()->scopedCall(client,alice,permissions,run));
    assertTrue(entered.await(3,TimeUnit.SECONDS));pool.submit(()->auth.revoke(alice)).get(1,TimeUnit.SECONDS);
    int decisions=db.tx(c->{try(var s=SecurityDatabase.statement(c,"SELECT count(*) FROM authorization_audit WHERE run_id=? AND phase='TOOL_DISPATCH' AND allowed",run);var rows=s.executeQuery()){rows.next();return rows.getInt(1);}});
    assertEquals(4,decisions);release.countDown();
    var failed=assertThrows(ExecutionException.class,()->pending.get(3,TimeUnit.SECONDS));assertInstanceOf(FormalKnowledgeMcpClient.Failure.class,failed.getCause());
   }finally{release.countDown();pool.shutdownNow();}
  }
 }
 @Test void replayTamperingWrongAudienceAndRevocationCannotRetrieve(){
  var calls=new AtomicInteger();try(var tool=new FormalKnowledgeMcpTool(tickets,policy,(actor,r)->{calls.incrementAndGet();return Map.of("tenant",actor.tenantId());})){
   var valid=wire(alice);assertFalse(tool.call(valid).isError());assertTrue(tool.call(valid).isError());
   var tampered=wire(alice);tampered.put("query","changed");assertTrue(tool.call(tampered).isError());
   var wrong=business();wrong.put("authorization",tickets.issue(alice,"knowledge-worker","knowledge.read",null,FormalKnowledgeMcpProtocol.requestHash(wrong)));assertTrue(tool.call(wrong).isError());
   var revoked=wire(bob);policy.setMembershipActive(bob,false);assertTrue(tool.call(revoked).isError());assertEquals(1,calls.get());
  }
 }
 @Test void revokeWhileRetrievalRunsBlocksResultPublication()throws Exception{
  var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();
  try(var tool=new FormalKnowledgeMcpTool(tickets,policy,(actor,r)->{entered.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}return Map.of("sensitive","must-not-publish");})){
   var args=wire(alice);var future=pool.submit(()->tool.call(args));assertTrue(entered.await(2,TimeUnit.SECONDS));policy.setMembershipActive(alice,false);release.countDown();
   var result=future.get(2,TimeUnit.SECONDS);assertTrue(result.isError());assertTrue(text(result).contains("MCP_NOT_AUTHORIZED"));assertFalse(text(result).contains("must-not-publish"));
  }finally{release.countDown();pool.shutdownNow();}
 }
 @Test void concurrentActorsUseOneAuthenticatedSseAndNeverShareTicketIdentity()throws Exception{
  String serviceToken="synthetic-mcp-internal-token-at-least-32-characters";var pool=Executors.newFixedThreadPool(2);
  try(var server=FormalKnowledgeMcpServer.start(0,new FormalKnowledgeMcpTool(tickets,policy,(actor,r)->Map.of("tenant",actor.tenantId())),serviceToken);
      var client=new FormalKnowledgeMcpClient(URI.create("http://127.0.0.1:"+server.port()),serviceToken,tickets,policy)){
   client.discover(Duration.ofSeconds(5),()->false);
   var a=pool.submit(()->client.call(alice,FormalKnowledgeMcpProtocol.EXPOSED_NAME,Map.of("query","same-query"),Duration.ofSeconds(5),()->false));
   var b=pool.submit(()->client.call(bob,FormalKnowledgeMcpProtocol.EXPOSED_NAME,Map.of("query","same-query"),Duration.ofSeconds(5),()->false));
   var ar=a.get(6,TimeUnit.SECONDS);var br=b.get(6,TimeUnit.SECONDS);assertFalse(ar.isError(),text(ar));assertFalse(br.isError(),text(br));
   assertTrue(text(ar).contains(alice.tenantId()));assertFalse(text(ar).contains(bob.tenantId()));assertTrue(text(br).contains(bob.tenantId()));assertFalse(text(br).contains(alice.tenantId()));assertEquals(1,server.activeConnections());
  }finally{pool.shutdownNow();}
 }
}
