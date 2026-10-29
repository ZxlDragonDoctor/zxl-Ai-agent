package com.zxl.zxlaiagent.knowledge.mcp;

import com.zxl.zxlaiagent.security.*;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FormalKnowledgeMcpTransportTest {
 private static final String SERVICE_TOKEN="test-service-token-with-32-characters-minimum";
 private final ActorRef alice=new ActorRef("alice","tenant-a","session-a",1),bob=new ActorRef("bob","tenant-b","session-b",1);
 private static AuthorizationService allowed(){
  var auth=mock(AuthorizationService.class);
  when(auth.decide(any(),anyString(),isNull(),any(),isNull())).thenReturn(new AuthorizationDecision("test","epoch:1",true,"ALLOWED"));
  when(auth.gate(any(),anyString(),isNull(),any(),isNull(),any())).thenAnswer(call->((Supplier<?>)call.getArgument(5)).get());
  when(auth.gateAll(any(),anyList(),any(),nullable(String.class),any())).thenAnswer(call->((Supplier<?>)call.getArgument(4)).get());return auth;
 }
 private static String text(McpSchema.CallToolResult result){return ((McpSchema.TextContent)result.content().get(0)).text();}
 @Test void internalServiceCredentialsNeverUseTheDefaultProxy(){
  var origin=URI.create("http://127.0.0.1:18135");
  var client=InternalServiceHttpClient.builder(SERVICE_TOKEN,origin).build();
  assertTrue(client.proxy().isPresent(),"An explicit direct selector is required");
  assertEquals(List.of(java.net.Proxy.NO_PROXY),client.proxy().orElseThrow().select(origin));
 }
 @Test void missingOrWrongServiceTokenCannotClaimSseOrPost()throws Exception{
  var tickets=mock(ServiceCallAuthorizer.class);var auth=allowed();
  try(var server=FormalKnowledgeMcpServer.start(0,new FormalKnowledgeMcpTool(tickets,auth,(actor,r)->Map.of()),SERVICE_TOKEN)){
   var http=HttpClient.newHttpClient();String base="http://127.0.0.1:"+server.port();
   for(String path:List.of("/sse","/mcp/message?sessionId=fake"))for(String token:List.of("","wrong")){
    var request=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(3));
    if(!token.isEmpty())request.header("Authorization","Bearer "+token);
    if(path.startsWith("/mcp"))request.POST(HttpRequest.BodyPublishers.ofString("{}"));else request.GET();
    assertEquals(401,http.send(request.build(),HttpResponse.BodyHandlers.discarding()).statusCode());
   }
   assertEquals(0,server.activeConnections());verifyNoInteractions(tickets);
  }
 }
 @Test void oneSharedSseConnectionCarriesIndependentActorTicketsAndModelSchemaHasNoSecrets(){
  var tickets=mock(ServiceCallAuthorizer.class);var auth=allowed();var actors=new ConcurrentHashMap<String,ActorRef>();
  when(tickets.issue(any(),eq(FormalKnowledgeMcpProtocol.AUDIENCE),eq("knowledge.read"),isNull(),anyString())).thenAnswer(call->{String token=UUID.randomUUID().toString();actors.put(token,call.getArgument(0));return token;});
  when(tickets.consume(anyString(),eq(FormalKnowledgeMcpProtocol.AUDIENCE),eq("knowledge.read"),anyString())).thenAnswer(call->{var actor=actors.remove(call.getArgument(0));if(actor==null)throw new SecurityFailure("INVALID_SERVICE_TICKET",401);return actor;});
  try(var server=FormalKnowledgeMcpServer.start(0,new FormalKnowledgeMcpTool(tickets,auth,(actor,r)->Map.of("tenant",actor.tenantId(),"query",r.query())),SERVICE_TOKEN);
      var client=new FormalKnowledgeMcpClient(URI.create("http://127.0.0.1:"+server.port()),SERVICE_TOKEN,tickets,auth)){
   var tools=client.discover(Duration.ofSeconds(5),()->false);assertEquals(1,tools.size());
   assertEquals(FormalKnowledgeMcpProtocol.EXPOSED_NAME,tools.get(0).definition().name());
   String schema=com.zxl.zxlaiagent.tools.support.ToolResults.json(tools.get(0).definition().inputSchema());
   assertFalse(schema.contains("authorization"));assertFalse(schema.contains("budgetMs"));assertNotEquals(tools.get(0).schemaSha256(),tools.get(0).wireSchemaSha256());
   for(var actor:List.of(alice,bob,alice,bob)){
    var result=client.call(actor,FormalKnowledgeMcpProtocol.EXPOSED_NAME,Map.of("query","same query"),Duration.ofSeconds(3),()->false);
    assertFalse(result.isError(),text(result));assertTrue(text(result).contains(actor.tenantId()));assertFalse(text(result).contains("authorization"));assertFalse(text(result).contains(SERVICE_TOKEN));
   }
   assertEquals(1,server.activeConnections());assertEquals(0,server.pendingRequests());assertTrue(actors.isEmpty());
  }
 }
 @Test void discoveryBudgetDoesNotPermanentlyShrinkLaterToolBudgets(){
  var tickets=mock(ServiceCallAuthorizer.class);var auth=allowed();var observed=new java.util.concurrent.atomic.AtomicLong();
  when(tickets.issue(any(),anyString(),anyString(),isNull(),anyString())).thenReturn("opaque-test-ticket");
  when(tickets.consume(anyString(),anyString(),anyString(),anyString())).thenReturn(alice);
  try(var server=FormalKnowledgeMcpServer.start(0,new FormalKnowledgeMcpTool(tickets,auth,(actor,r)->{observed.set(r.budgetMs());return Map.of("hits",List.of());}),SERVICE_TOKEN);
      var client=new FormalKnowledgeMcpClient(URI.create("http://127.0.0.1:"+server.port()),SERVICE_TOKEN,tickets,auth)){
   assertEquals(1,client.discover(Duration.ofSeconds(5),()->false).size());
   assertFalse(client.call(alice,FormalKnowledgeMcpProtocol.EXPOSED_NAME,Map.of("query","test"),Duration.ofSeconds(9),()->false).isError());
   assertTrue(observed.get()>6000,"Five-second discovery must not pin the shared SDK session to five seconds: "+observed.get());
   assertFalse(client.call(alice,FormalKnowledgeMcpProtocol.EXPOSED_NAME,Map.of("query","test"),Duration.ofSeconds(2),()->false).isError());
   assertTrue(observed.get()>0&&observed.get()<2000,"Each shorter caller deadline must still apply");
   assertEquals(1,server.activeConnections());
  }
 }
 @Test void consumedTicketIsNotRetriedAndRevokedPublishDiscardsSensitiveResult(){
  var tickets=mock(ServiceCallAuthorizer.class);var auth=allowed();var hits=new AtomicInteger();
  when(tickets.consume(anyString(),anyString(),anyString(),anyString())).thenReturn(alice).thenThrow(new SecurityFailure("INVALID_SERVICE_TICKET",401));
  var args=new HashMap<String,Object>(Map.of("query","secret","mode","keyword","topK",1,"budgetMs",1000,"authorization","one-use"));
  try(var tool=new FormalKnowledgeMcpTool(tickets,auth,(actor,r)->{hits.incrementAndGet();return Map.of("secret","sensitive-result");})){
   assertFalse(tool.call(args).isError());assertTrue(tool.call(args).isError());assertEquals(1,hits.get());
  }
  var revoke=allowed();doThrow(new SecurityFailure("ACTION_DENIED",403)).when(revoke).gate(any(),anyString(),isNull(),eq(Phase.RESULT_PUBLISH),isNull(),any());
  var fresh=mock(ServiceCallAuthorizer.class);when(fresh.consume(anyString(),anyString(),anyString(),anyString())).thenReturn(bob);
  try(var tool=new FormalKnowledgeMcpTool(fresh,revoke,(actor,r)->Map.of("secret","sensitive-result"))){var result=tool.call(args);assertTrue(result.isError());assertFalse(text(result).contains("sensitive-result"));assertTrue(text(result).contains("MCP_NOT_AUTHORIZED"));}
 }
 @Test void forgedReservedFieldsAndUnsafeTypesNeverReachTicketConsumer(){
  var tickets=mock(ServiceCallAuthorizer.class);var auth=allowed();
  try(var tool=new FormalKnowledgeMcpTool(tickets,auth,(a,r)->{fail("Should not retrieve");return null;})){
   for(var args:List.of(Map.<String,Object>of("query","x"),Map.<String,Object>of("query","x","topK",1,"mode","keyword","budgetMs",1000,"authorization","t","tenant","other"),Map.<String,Object>of("query","x","topK",1.0,"mode","keyword","budgetMs",1000,"authorization","t")))assertTrue(tool.call(args).isError());
   verifyNoInteractions(tickets);
  }
 }
 public static void main(String[] args)throws Exception{
  var test=new FormalKnowledgeMcpTransportTest();test.missingOrWrongServiceTokenCannotClaimSseOrPost();test.oneSharedSseConnectionCarriesIndependentActorTicketsAndModelSchemaHasNoSecrets();test.consumedTicketIsNotRetriedAndRevokedPublishDiscardsSensitiveResult();test.forgedReservedFieldsAndUnsafeTypesNeverReachTicketConsumer();System.out.println("FORMAL_MCP_TRANSPORT_PROBE_PASS=4");
 }
}
