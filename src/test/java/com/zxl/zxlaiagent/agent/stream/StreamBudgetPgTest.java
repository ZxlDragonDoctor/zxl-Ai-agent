package com.zxl.zxlaiagent.agent.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.zxl.zxlaiagent.security.*;
import com.zxl.zxlaiagent.security.execution.OperationBoundary;
import com.zxl.zxlaiagent.usage.*;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

class StreamBudgetPgTest {
 SecurityDatabase db; TokenBudgetLedger ledger; String tenant; HttpServer server; AtomicInteger requests=new AtomicInteger();
 @BeforeEach void setup()throws Exception{db=SecurityDatabase.fromEnvironment();tenant="stream-budget-"+UUID.randomUUID();db.tx(c->{try(var s=SecurityDatabase.statement(c,"INSERT INTO tenant(tenant_id) VALUES (?)",tenant)){s.executeUpdate();}return null;});ledger=new TokenBudgetLedger(db,tenant);ledger.createAccount("run",100,10);server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);}
 @AfterEach void cleanup(){server.stop(0);db.tx(c->{try(var s=SecurityDatabase.statement(c,"DELETE FROM tenant WHERE tenant_id=?",tenant)){s.executeUpdate();}return null;});db.close();}
 DashScopeAgentStreamer provider(String usage){server.createContext("/chat/completions",e->{requests.incrementAndGet();e.getRequestBody().readAllBytes();e.getResponseHeaders().set("Content-Type","text/event-stream");e.sendResponseHeaders(200,0);String frame="data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]"+usage+"}\n\ndata: [DONE]\n\n";e.getResponseBody().write(frame.getBytes(StandardCharsets.UTF_8));e.close();});server.start();return new DashScopeAgentStreamer(new ObjectMapper(),"synthetic","http://127.0.0.1:"+server.getAddress().getPort(),"fixture",false,0);}
 void call(DashScopeAgentStreamer streamer,String id,long estimate){streamer.stream("system",List.of(new UserMessage("question")),new ToolCallback[0],(a,b)->{},()->false,OperationBoundary.unrestricted(),new TokenCallBudget(ledger,"run",id,estimate));}
 @Test void knownUsageSettlesAndReplayCannotIssueAnotherRequest(){var s=provider(",\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":3,\"total_tokens\":5}");call(s,"one",20);assertEquals(5,ledger.account("run").spent());assertEquals(0,ledger.account("run").reserved());assertThrows(RuntimeException.class,()->call(s,"one",20));assertEquals(1,requests.get());}
 @Test void missingUsageRemainsUnknown(){call(provider(""),"one",20);assertEquals(20,ledger.account("run").reserved());assertEquals(0,ledger.account("run").spent());assertThrows(RuntimeException.class,()->ledger.releaseUnsent("run","one"));}
 @Test void overageIsRecordedAndNextRequestIsRejected(){var s=provider(",\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":20,\"total_tokens\":120}");call(s,"one",20);assertEquals(120,ledger.account("run").spent());assertThrows(RuntimeException.class,()->call(s,"two",1));assertEquals(1,requests.get());}
 @Test void cancelledBeforeDispatchReleasesOnlyUnsentTokens(){var s=provider("");assertThrows(CancellationException.class,()->s.stream("system",List.of(new UserMessage("q")),new ToolCallback[0],(a,b)->{},()->true,OperationBoundary.unrestricted(),new TokenCallBudget(ledger,"run","one",20)));assertEquals(0,requests.get());assertEquals(0,ledger.account("run").reserved());}
 @Test void cancelledAfterDispatchKeepsUnknownReservation(){var cancelled=new AtomicBoolean();var s=provider("");assertThrows(CancellationException.class,()->s.stream("system",List.of(new UserMessage("q")),new ToolCallback[0],(a,b)->cancelled.set(true),cancelled::get,OperationBoundary.unrestricted(),new TokenCallBudget(ledger,"run","one",20)));assertEquals(1,requests.get());assertEquals(20,ledger.account("run").reserved());}
 @Test void usageReceivedBeforePublicationRejectionStillSettles(){var s=provider(",\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":3,\"total_tokens\":5}");var deltas=new AtomicInteger();OperationBoundary denied=new OperationBoundary(){public void check(Phase p){if(p==Phase.RESULT_PUBLISH)throw new SecurityFailure("ACTION_DENIED",403);}public <T>T start(Phase p,Supplier<T> a){return a.get();}};assertThrows(SecurityFailure.class,()->s.stream("system",List.of(new UserMessage("q")),new ToolCallback[0],(a,b)->deltas.incrementAndGet(),()->false,denied,new TokenCallBudget(ledger,"run","one",20)));assertEquals(0,deltas.get());assertEquals(5,ledger.account("run").spent());}
 @Test void settlementFailureCannotTurnAResponseIntoSuccess(){var s=provider("");ModelCallBudget broken=()->new ModelCallBudget.Reservation(){public void dispatch(){}public void finish(Long n){throw new IllegalStateException("ACCOUNTING_UNAVAILABLE");}};var failure=assertThrows(IllegalStateException.class,()->s.stream("system",List.of(new UserMessage("q")),new ToolCallback[0],(a,b)->{},()->false,OperationBoundary.unrestricted(),broken));assertEquals("ACCOUNTING_UNAVAILABLE",failure.getMessage());}
 @Test void accountingFailureIsSuppressedOntoOriginalCancellation(){var s=provider("");ModelCallBudget broken=()->new ModelCallBudget.Reservation(){public void dispatch(){}public void finish(Long n){throw new IllegalStateException("ACCOUNTING_UNAVAILABLE");}};var failure=assertThrows(CancellationException.class,()->s.stream("system",List.of(new UserMessage("q")),new ToolCallback[0],(a,b)->{},()->true,OperationBoundary.unrestricted(),broken));assertEquals(1,failure.getSuppressed().length);assertEquals("ACCOUNTING_UNAVAILABLE",failure.getSuppressed()[0].getMessage());assertEquals(0,requests.get());}
 @Test void inconsistentUsageDoesNotBecomeKnownZero(){call(provider(",\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":3,\"total_tokens\":0}"),"one",20);assertEquals(20,ledger.account("run").reserved());assertEquals(0,ledger.account("run").spent());}
}
