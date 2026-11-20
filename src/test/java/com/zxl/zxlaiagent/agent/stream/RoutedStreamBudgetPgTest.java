package com.zxl.zxlaiagent.agent.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.zxl.zxlaiagent.security.SecurityDatabase;
import com.zxl.zxlaiagent.security.execution.OperationBoundary;
import com.zxl.zxlaiagent.usage.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RoutedStreamBudgetPgTest {
 @Test void fallbackUsesDistinctActualModelAndNewReservation()throws Exception {
  try(var db=SecurityDatabase.fromEnvironment()) {
   String tenant="route-"+UUID.randomUUID();
   db.tx(c->{try(var s=SecurityDatabase.statement(c,"INSERT INTO tenant(tenant_id) VALUES (?)",tenant)){s.executeUpdate();}return null;});
   var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
   try {
    var ledger=new TokenBudgetLedger(db,tenant);ledger.createAccount("run",100,3);
    var requests=new AtomicInteger();var identities=new ArrayList<String>();var mapper=new ObjectMapper();
    server.createContext("/chat/completions",exchange->{
     identities.add(mapper.readTree(exchange.getRequestBody().readAllBytes()).path("model").asText());
     if(requests.incrementAndGet()==1){exchange.sendResponseHeaders(503,-1);exchange.close();return;}
     exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);
     exchange.getResponseBody().write(("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":3,\"total_tokens\":5}}\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));exchange.close();
    });server.start();
    var streamer=new DashScopeAgentStreamer(mapper,"synthetic","http://127.0.0.1:"+server.getAddress().getPort(),"default",false,0);
    var attempt=new AtomicInteger();
    var response=streamer.streamRouted("system",List.of(new UserMessage("question")),new ToolCallback[0],(a,b)->{},()->false,OperationBoundary.unrestricted(),()->new TokenCallBudget(ledger,"run","call-"+attempt.incrementAndGet(),20),new BoundedModelRouter("primary","secondary"));
    assertEquals("ok",response.getResult().getOutput().getText());assertEquals(List.of("primary","secondary"),identities);
    assertEquals(2,ledger.account("run").calls());assertEquals(20,ledger.account("run").reserved());assertEquals(5,ledger.account("run").spent());
   } finally {
    server.stop(0);db.tx(c->{try(var s=SecurityDatabase.statement(c,"DELETE FROM tenant WHERE tenant_id=?",tenant)){s.executeUpdate();}return null;});
   }
  }
 }
}
