package com.zxl.zxlaiagent.agent.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.zxl.zxlaiagent.security.*;
import com.zxl.zxlaiagent.security.execution.*;
import com.zxl.zxlaiagent.usage.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

class StreamBudgetPoolPgTest {
 @Test void concurrentAuthorizedStreamsDoNotAcquireNestedPoolConnections() throws Exception {
  try(var db=SecurityDatabase.pooled(System.getenv("LESSON12_DB_URL"),System.getenv("LESSON12_DB_USER"),System.getenv("LESSON12_DB_PASSWORD"),1,1000)) {
   String tenant="pool-budget-"+UUID.randomUUID();var auth=new AuthenticationService(db);
   var actors=new ArrayList<ActorRef>();
   for(int i=0;i<2;i++){String id=tenant+"-"+i;auth.provision(id,id,"synthetic-password",tenant,"employee");actors.add(auth.login(id,"synthetic-password",tenant).actor().ref());}
   var ledger=new TokenBudgetLedger(db,tenant);ledger.createAccount("run",100,4);
   var requests=new AtomicInteger();var server=server(requests);var pool=Executors.newFixedThreadPool(2);
   try {
    var streamer=streamer(server);var policy=new AuthorizationService(db);var ready=new CountDownLatch(1);var jobs=new ArrayList<Future<String>>();
    for(int i=0;i<2;i++){final int index=i;jobs.add(pool.submit(()->{ready.await();return call(streamer,new ActorBoundary(policy,actors.get(index),List.of(new AuthorizationService.Permission("knowledge.read",null)),null),new TokenCallBudget(ledger,"run","call-"+index,20));}));}
    ready.countDown();
    for(var job:jobs)assertEquals("ok",job.get(12,TimeUnit.SECONDS));
    assertEquals(2,requests.get());assertEquals(10,ledger.account("run").spent());assertEquals(0,ledger.account("run").reserved());
   } finally {
    pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));server.stop(0);
    db.tx(c->{for(var actor:actors){try(var s=SecurityDatabase.statement(c,"DELETE FROM subject WHERE subject_id=?",actor.subjectId())){s.executeUpdate();}}try(var s=SecurityDatabase.statement(c,"DELETE FROM tenant WHERE tenant_id=?",tenant)){s.executeUpdate();}return null;});
   }
  }
 }
 @Test void finalAuthorizationDenialKeepsClaimedReservationUnknownWithoutSending() throws Exception {
  try(var db=SecurityDatabase.fromEnvironment()) {
   String tenant="denied-budget-"+UUID.randomUUID();
   db.tx(c->{try(var s=SecurityDatabase.statement(c,"INSERT INTO tenant(tenant_id) VALUES (?)",tenant)){s.executeUpdate();}return null;});
   var ledger=new TokenBudgetLedger(db,tenant);ledger.createAccount("run",100,2);var requests=new AtomicInteger();var server=server(requests);
   try {
    OperationBoundary denied=new OperationBoundary(){public void check(Phase phase){}public <T>T start(Phase phase,Supplier<T> operation){throw new SecurityFailure("ACTION_DENIED",403);}};
    assertThrows(SecurityFailure.class,()->call(streamer(server),denied,new TokenCallBudget(ledger,"run","call",20)));
    assertEquals(0,requests.get());assertEquals(20,ledger.account("run").reserved());
    assertEquals("UNKNOWN",db.tx(c->{try(var s=SecurityDatabase.statement(c,"SELECT state FROM token_budget_call WHERE tenant_id=? AND account_id='run' AND call_id='call'",tenant);var r=s.executeQuery()){assertTrue(r.next());return r.getString(1);}}));
   } finally {server.stop(0);db.tx(c->{try(var s=SecurityDatabase.statement(c,"DELETE FROM tenant WHERE tenant_id=?",tenant)){s.executeUpdate();}return null;});}
  }
 }
 private static HttpServer server(AtomicInteger requests)throws Exception {
  var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/chat/completions",exchange->{requests.incrementAndGet();exchange.getRequestBody().readAllBytes();exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);exchange.getResponseBody().write("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":3,\"total_tokens\":5}}\n\ndata: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));exchange.close();});server.start();return server;
 }
 private static DashScopeAgentStreamer streamer(HttpServer server){return new DashScopeAgentStreamer(new ObjectMapper(),"synthetic","http://127.0.0.1:"+server.getAddress().getPort(),"fixture",false,0);}
 private static String call(DashScopeAgentStreamer streamer,OperationBoundary boundary,ModelCallBudget budget){return streamer.stream("system",List.of(new UserMessage("question")),new ToolCallback[0],(a,b)->{},()->false,boundary,budget).getResult().getOutput().getText();}
}
