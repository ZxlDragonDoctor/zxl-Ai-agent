package com.zxl.zxlaiagent.research;

import com.zxl.zxlaiagent.multiagent.agents.ResearchAgent;
import com.zxl.zxlaiagent.multiagent.bus.AgentMessageBus;
import com.zxl.zxlaiagent.multiagent.execution.TaskPromptRenderer;
import com.zxl.zxlaiagent.multiagent.model.*;
import com.zxl.zxlaiagent.knowledge.mcp.KnowledgeLookupTool;
import com.fasterxml.jackson.databind.*;
import org.springframework.ai.chat.model.ChatModel;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResearchAgentIntegrationTest {
 @Test void workflowResearchUsesRealExecutorAndCountsBothModelCalls()throws Exception{
  var calls=new AtomicInteger();var runner=new ResearchRunner(gateway(calls),model(null,null),(h,d,c)->h);
  var agent=new ResearchAgent(mock(ChatModel.class),new TaskPromptRenderer(new ObjectMapper()),runner);agent.attachMessageBus(mock(AgentMessageBus.class));
  var task=Task.create("VPN401");task.setInput(Map.of());
  assertTrue(agent.executeTask(task).get(3,TimeUnit.SECONDS).contains("[C1]"));
  assertEquals(2,task.snapshot().modelCalls());assertEquals(TaskStatus.COMPLETED,task.snapshot().status());assertEquals(1,calls.get());
  assertEquals(Set.of("knowledge__knowledge_lookup_v1"),agent.getCapability().getTools());
 }
 @Test void terminalTaskCannotStartToolAfterLateModelSelection()throws Exception{
  var calls=new AtomicInteger();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
  var agent=new ResearchAgent(mock(ChatModel.class),new TaskPromptRenderer(new ObjectMapper()),new ResearchRunner(gateway(calls),model(entered,release),(h,d,c)->h));agent.attachMessageBus(mock(AgentMessageBus.class));
  var task=Task.create("VPN401");task.setInput(Map.of());var result=agent.executeTask(task);
  try{assertTrue(entered.await(2,TimeUnit.SECONDS));TaskLifecycle.cancel(task,new WorkflowError("RUN_CANCELLED","task","cancelled"));release.countDown();assertThrows(ExecutionException.class,()->result.get(2,TimeUnit.SECONDS));assertEquals(0,calls.get());assertEquals(TaskStatus.CANCELLED,task.snapshot().status());}
  finally{release.countDown();}
 }
 private ResearchRunner.Gateway gateway(AtomicInteger calls){return new ResearchRunner.Gateway(){
  public List<ResearchRunner.Tool> discover(Duration d,BooleanSupplier c){return List.of(new ResearchRunner.Tool("knowledge__knowledge_lookup_v1","lookup",KnowledgeLookupTool.SCHEMA));}
  public String call(String n,String a,Duration d,BooleanSupplier c){calls.incrementAndGet();return "{\"success\":true,\"code\":\"OK\",\"message\":\"done\",\"data\":{\"hits\":[{\"documentId\":\"vpn\",\"chunkId\":\"c\",\"text\":\"SSO\"}]}}";}
 };}
 private ResearchRunner.Model model(CountDownLatch entered,CountDownLatch release){return new ResearchRunner.Model(){
  public ResearchRunner.Selection select(String q,List<ResearchRunner.Tool> t,Duration d,BooleanSupplier c){if(entered!=null){entered.countDown();try{release.await(2,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new CancellationException();}}return new ResearchRunner.Selection("one",t.get(0).name(),"{\"query\":\"VPN401\"}");}
  public String answer(String q,List<JsonNode> h,Duration d,BooleanSupplier c){return "通过SSO更新凭据 [C1]";}
 };}
}
