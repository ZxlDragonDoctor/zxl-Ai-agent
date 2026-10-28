package com.zxl.zxlaiagent.research;

import com.fasterxml.jackson.databind.*;
import com.zxl.zxlaiagent.agent.model.AgentExecutionEvent;
import com.zxl.zxlaiagent.agent.stream.DashScopeAgentStreamer;
import com.zxl.zxlaiagent.knowledge.gateway.ScopedKnowledgeClient;
import com.zxl.zxlaiagent.knowledge.mcp.*;
import com.zxl.zxlaiagent.security.*;
import com.zxl.zxlaiagent.security.execution.OperationBoundary;
import com.zxl.zxlaiagent.tools.support.ToolResults;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.tool.ToolCallback;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ScopedResearchTest {
 private static final Duration BUDGET=Duration.ofSeconds(3);
 private static final ObjectMapper JSON=new ObjectMapper();
 private static final String HIT="{\"documentId\":\"vpn\",\"revisionId\":\"r1\",\"generation\":1,\"chunkId\":\"c1\",\"text\":\"SSO\",\"startUtf16\":0,\"endUtf16\":3,\"startLine\":1,\"endLine\":1}";
 private static final String RESULT="{\"success\":true,\"code\":\"OK\",\"message\":\"done\",\"data\":{\"hits\":["+HIT+"]}}";
 private static final String DETAIL="{\"document\":{\"documentId\":\"vpn\",\"status\":\"ACTIVE\",\"activeRevision\":\"r1\",\"version\":1},\"rawText\":\"SSO\",\"chunks\":[{\"chunkId\":\"c1\",\"text\":\"SSO\",\"startUtf16\":0,\"endUtf16\":3}]}";
 @Test void formalRunnerMustHaveReviewedV2Entry()throws Exception{assertNotNull(ResearchRunner.class.getMethod("authorized",ResearchRunner.Gateway.class,ResearchRunner.Model.class,ResearchRunner.Verifier.class,OperationBoundary.class));}
 @Test void alreadyAuthorizedAgentPublisherMustNotNestPgGates()throws Exception{
  var inside=new AtomicBoolean();var events=new ArrayList<AgentExecutionEvent>();
  OperationBoundary boundary=new OperationBoundary(){
   public void check(Phase phase){}
   public <T>T start(Phase phase,Supplier<T> action){if(!inside.compareAndSet(false,true))throw new IllegalStateException("NESTED_AUTHORIZATION_GATE");try{return action.get();}finally{inside.set(false);}}
  };
  Consumer<AgentExecutionEvent> guarded=event->boundary.start(Phase.RESULT_PUBLISH,()->{events.add(event);return null;});
  var runner=ResearchRunner.authorized(gateway(tool(FormalKnowledgeMcpProtocol.EXPOSED_NAME,FormalKnowledgeMcpProtocol.MODEL_SCHEMA),new AtomicInteger(),()->{}),model("{\"query\":\"VPN\"}",new AtomicInteger(),new AtomicInteger()),(h,d,c)->h,boundary);
  assertThrows(IllegalStateException.class,()->runner.run("VPN",BUDGET,()->false,guarded));events.clear();
  var method=ResearchRunner.class.getMethod("runWithAuthorizedPublisher",String.class,Duration.class,BooleanSupplier.class,Consumer.class);
  assertTrue(((String)method.invoke(runner,"VPN",BUDGET,(BooleanSupplier)()->false,guarded)).contains("[C1]"));
  assertTrue(events.size()>=3);assertFalse(inside.get());
 }
 private static ResearchRunner.Tool tool(String name,String schema){return new ResearchRunner.Tool(name,"lookup",schema);}
 private static ResearchRunner.Gateway gateway(ResearchRunner.Tool offered,AtomicInteger calls,Runnable onCall){return new ResearchRunner.Gateway(){
  public List<ResearchRunner.Tool> discover(Duration d,BooleanSupplier c){return List.of(offered);}
  public String call(String n,String a,Duration d,BooleanSupplier c){calls.incrementAndGet();onCall.run();return RESULT;}
 };}
 private static ResearchRunner.Model model(String arguments,AtomicInteger selections,AtomicInteger answers){return new ResearchRunner.Model(){
  public ResearchRunner.Selection select(String q,List<ResearchRunner.Tool> t,Duration d,BooleanSupplier c){selections.incrementAndGet();return new ResearchRunner.Selection("call1",t.get(0).name(),arguments);}
  public String answer(String q,List<JsonNode> h,Duration d,BooleanSupplier c){answers.incrementAndGet();return "SSO [C1]";}
 };}
 private static OperationBoundary revocable(AtomicBoolean revoked,List<Phase> phases){return new OperationBoundary(){
  public void check(Phase phase){synchronized(phases){phases.add(phase);}if(revoked.get())throw new SecurityFailure("ACTION_DENIED",403);}
  public <T>T start(Phase phase,Supplier<T> action){check(phase);return action.get();}
 };}
 @Test void formalV2WorksAndLegacyOrWireSchemaNeverReachModel(){
  var calls=new AtomicInteger();var selects=new AtomicInteger();var answers=new AtomicInteger();
  var accepted=ResearchRunner.authorized(gateway(tool(FormalKnowledgeMcpProtocol.EXPOSED_NAME,FormalKnowledgeMcpProtocol.MODEL_SCHEMA),calls,()->{}),model("{\"query\":\"VPN\"}",selects,answers),(h,d,c)->h,OperationBoundary.unrestricted());
  assertTrue(accepted.run("VPN",BUDGET,()->false,e->{}).contains("[C1]"));assertEquals(1,calls.get());assertEquals(1,answers.get());
  for(var invalid:List.of(tool(KnowledgeMcpClient.EXPOSED_NAME,KnowledgeLookupTool.SCHEMA),tool(FormalKnowledgeMcpProtocol.EXPOSED_NAME,FormalKnowledgeMcpProtocol.WIRE_SCHEMA))){
   selects.set(0);calls.set(0);var denied=ResearchRunner.authorized(gateway(invalid,calls,()->{}),model("{}",selects,answers),(h,d,c)->h,OperationBoundary.unrestricted());
   assertThrows(IllegalStateException.class,()->denied.run("VPN",BUDGET,()->false,e->{}));assertEquals(0,selects.get());assertEquals(0,calls.get());
  }
 }
 @Test void reservedAuthorizationNeverEntersEventsOrToolCalls(){
  var calls=new AtomicInteger();var events=new ArrayList<AgentExecutionEvent>();
  var runner=ResearchRunner.authorized(gateway(tool(FormalKnowledgeMcpProtocol.EXPOSED_NAME,FormalKnowledgeMcpProtocol.MODEL_SCHEMA),calls,()->{}),model("{\"query\":\"VPN\",\"authorization\":\"secret-ticket\"}",new AtomicInteger(),new AtomicInteger()),(h,d,c)->h,OperationBoundary.unrestricted());
  assertThrows(RuntimeException.class,()->runner.run("VPN",BUDGET,()->false,events::add));assertEquals(0,calls.get());assertTrue(events.isEmpty());
 }
 @Test void revocationAfterToolReturnBlocksSensitiveEventAndSynthesis(){
  var revoked=new AtomicBoolean();var phases=new ArrayList<Phase>();var calls=new AtomicInteger();var answers=new AtomicInteger();var events=new ArrayList<AgentExecutionEvent>();
  var runner=ResearchRunner.authorized(gateway(tool(FormalKnowledgeMcpProtocol.EXPOSED_NAME,FormalKnowledgeMcpProtocol.MODEL_SCHEMA),calls,()->revoked.set(true)),model("{\"query\":\"VPN\"}",new AtomicInteger(),answers),(h,d,c)->h,revocable(revoked,phases));
  assertThrows(SecurityFailure.class,()->runner.run("VPN",BUDGET,()->false,events::add));assertEquals(1,calls.get());assertEquals(0,answers.get());assertEquals(List.of("TOOL_REQUESTED","TOOL_STARTED"),events.stream().map(AgentExecutionEvent::type).toList());assertTrue(phases.contains(Phase.DEQUEUE));
 }
 @Test void modelAuthorizationFailuresKeepTypedSecurityMeaningOnSixArgumentStream(){
  var streamer=mock(DashScopeAgentStreamer.class);var boundary=OperationBoundary.unrestricted();var denied=new SecurityFailure("ACTION_DENIED",403);
  when(streamer.stream(anyString(),anyList(),any(ToolCallback[].class),any(),any(),same(boundary))).thenThrow(new IllegalStateException("private",denied));
  var model=new StreamingResearchModel(streamer,boundary);
  assertSame(denied,assertThrows(SecurityFailure.class,()->model.select("VPN",List.of(),BUDGET,()->false)));
  assertSame(denied,assertThrows(SecurityFailure.class,()->model.answer("VPN",List.of(),BUDGET,()->false)));
  verify(streamer,never()).stream(anyString(),anyList(),any(ToolCallback[].class),any(),any());
 }
 @Test void formalVerifierUsesOnlyInjectedPortAndRechecksSource()throws Exception{
  var reads=new AtomicInteger();var phases=new ArrayList<Phase>();var verifier=new McpEvidenceVerifier((id,d,c)->{assertEquals("vpn",id);reads.incrementAndGet();try{return JSON.readTree(DETAIL);}catch(Exception e){throw new RuntimeException(e);}},revocable(new AtomicBoolean(),phases));
  var hit=JSON.readTree(HIT);assertEquals(List.of(hit),verifier.verify(List.of(hit),BUDGET,()->false));assertEquals(List.of(hit),verifier.verify(List.of(hit),BUDGET,()->false));assertEquals(2,reads.get());assertTrue(phases.contains(Phase.SOURCE_READ));
 }
 @Test void factoryCapturesSameActorForMcpAndTicketedSourceRead()throws Exception{
  var mcp=mock(FormalKnowledgeMcpClient.class);var worker=mock(ScopedKnowledgeClient.class);var streamer=mock(DashScopeAgentStreamer.class);var auth=mock(AuthorizationService.class);
  var definition=FormalKnowledgeMcpProtocol.modelDefinition();
  when(mcp.discover(any(),any())).thenReturn(List.of(new FormalKnowledgeMcpClient.DiscoveredTool("knowledge",FormalKnowledgeMcpProtocol.NAME,FormalKnowledgeMcpProtocol.EXPOSED_NAME,"v2","model-digest","wire-digest",definition)));
  when(mcp.call(any(),eq(FormalKnowledgeMcpProtocol.EXPOSED_NAME),anyMap(),any(),any(),anyList(),isNull())).thenReturn(new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(RESULT)),false));
  when(worker.exchange(any(),eq("GET"),eq("/ai/learning/knowledge/documents/vpn"),isNull(),any(byte[].class),any(),any(),anyList(),isNull())).thenReturn(new ScopedKnowledgeClient.Response(200,DETAIL.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  var select=new ChatResponse(List.of(new Generation(new AssistantMessage("",Map.of(),List.of(new AssistantMessage.ToolCall("call1","function",FormalKnowledgeMcpProtocol.EXPOSED_NAME,"{\"query\":\"VPN\"}"))))));
  var answer=new ChatResponse(List.of(new Generation(new AssistantMessage("SSO [C1]"))));
  when(streamer.stream(anyString(),anyList(),any(ToolCallback[].class),any(),any(),any())).thenReturn(select,answer,select,answer);
  var factory=new ScopedResearchFactory(mcp,worker,streamer,auth);var alice=new ActorRef("a","ta","sa",1);var bob=new ActorRef("b","tb","sb",1);
  for(var actor:List.of(alice,bob)){var events=new ArrayList<AgentExecutionEvent>();assertTrue(factory.create(actor,OperationBoundary.unrestricted()).run("VPN",BUDGET,()->false,events::add).contains("[C1]"));verify(mcp).call(eq(actor),eq(FormalKnowledgeMcpProtocol.EXPOSED_NAME),anyMap(),any(),any(),anyList(),isNull());verify(worker,times(3)).exchange(eq(actor),eq("GET"),eq("/ai/learning/knowledge/documents/vpn"),isNull(),any(byte[].class),any(),any(),anyList(),isNull());for(var event:events)assertFalse(ToolResults.json(event).contains("authorization"));}
  verify(streamer,never()).stream(anyString(),anyList(),any(ToolCallback[].class),any(),any());
 }
 public static void main(String[] args)throws Exception{var t=new ScopedResearchTest();t.formalRunnerMustHaveReviewedV2Entry();t.formalV2WorksAndLegacyOrWireSchemaNeverReachModel();t.reservedAuthorizationNeverEntersEventsOrToolCalls();t.revocationAfterToolReturnBlocksSensitiveEventAndSynthesis();t.modelAuthorizationFailuresKeepTypedSecurityMeaningOnSixArgumentStream();t.formalVerifierUsesOnlyInjectedPortAndRechecksSource();t.factoryCapturesSameActorForMcpAndTicketedSourceRead();System.out.println("SCOPED_RESEARCH_PROBE_PASS=7");}
}
