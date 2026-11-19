package com.zxl.zxlaiagent.multiagent;

import com.zxl.zxlaiagent.multiagent.agents.*;
import com.zxl.zxlaiagent.multiagent.bus.AgentMessageBus;
import com.zxl.zxlaiagent.multiagent.execution.*;
import com.zxl.zxlaiagent.multiagent.model.*;
import com.zxl.zxlaiagent.multiagent.orchestration.OrchestratorAgent;
import com.zxl.zxlaiagent.multiagent.runtime.*;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.model.ChatModel;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Controlled Java integration; only the global ticket write count uses real PostgreSQL. */
class Lesson22BudgetPhasesTest {
 final ChatModel model=mock(ChatModel.class);
 final AgentMessageBus bus=mock(AgentMessageBus.class);
 final AtomicLong clock=new AtomicLong(System.nanoTime());
 final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
 OrchestratorAgent orchestrator;
 com.zxl.zxlaiagent.security.SecurityDatabase database;
 long beforeWrites;
 @BeforeEach void observeWrites(){database=com.zxl.zxlaiagent.security.SecurityDatabase.fromEnvironment();beforeWrites=countWrites();}
 long countWrites(){return database.tx(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM lesson17.approved_ticket")){r.next();return r.getLong(1);}});}
 void record(String id,String phase,OrchestratorAgent.RunHandle handle,WorkflowOutcome result)throws Exception{
  long after=countWrites();assertEquals(beforeWrites,after,"Controlled phases may not write tickets");
  var observation=new LinkedHashMap<String,Object>();observation.put("id",id);observation.put("runId",handle.executionId());observation.put("reachedPhase",phase);observation.put("phaseConfirmed",true);observation.put("clockMode","controlled");observation.put("model","controlled-java-fixture");observation.put("countsScope","database-global");observation.put("beforeWrites",beforeWrites);observation.put("afterWrites",after);observation.put("errorCode",result.error().code());observation.put("outcomeStatus",result.status());observation.put("cleanupConfirmed",!orchestrator.runStatus(handle.executionId()).runtime().cleanupPending());observation.put("activeTasks",orchestrator.runStatus(handle.executionId()).runtime().activeTasks());observation.put("elapsedMs",result.finishedAt()-result.startedAt());
  new com.fasterxml.jackson.databind.ObjectMapper().writeValue(java.nio.file.Path.of("target/lesson22-"+id+"-controlled.json").toFile(),observation);
 }
 static final String ONE="[{\"task_description\":\"controlled research\",\"dependencies\":[],\"required_capability\":\"research\"}]";
 void init(int workers){var settings=new RuntimeSettings();settings.setWorkerThreads(workers);orchestrator=new OrchestratorAgent(model,settings,clock::get);orchestrator.init(bus);}
 void register(AbstractBaseAgent agent){agent.attachMessageBus(bus);orchestrator.registerAgent(agent);}
 String block(String response){entered.countDown();try{assertTrue(release.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}return response;}
 void planner(){register(new PlannerAgent(model){@Override protected String callLlm(String s,String p){return ONE;}});}
 WorkflowOutcome expire(OrchestratorAgent.RunHandle handle)throws Exception {clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(1001));orchestrator.sweepRuns();var result=handle.result().get(2,TimeUnit.SECONDS);assertEquals("failed",result.status());assertEquals("RUN_DEADLINE_EXCEEDED",result.error().code());return result;}
 void awaitClean(String id)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(orchestrator.runStatus(id).runtime().cleanupPending()&&System.nanoTime()<end)Thread.sleep(5);assertFalse(orchestrator.runStatus(id).runtime().cleanupPending());assertEquals(0,orchestrator.runStatus(id).runtime().activeTasks());}
 @AfterEach void cleanup(){release.countDown();if(orchestrator!=null)orchestrator.shutdown();if(database!=null)database.close();}
 @Test void budget4PlannerWaitKeepsOwnershipUntilActualReturn()throws Exception{
  init(2);var planner=new PlannerAgent(model){@Override protected String callLlm(String s,String p){return block(ONE);}};register(planner);
  var starts=new AtomicInteger();register(new AbstractBaseAgent("research-fixture","research",model){@Override public CompletableFuture<String> processTask(Task t){starts.incrementAndGet();return CompletableFuture.completedFuture("unexpected");}});
  var handle=orchestrator.submit("Find the supplies request process.",Map.of(),1000L,r->{});assertTrue(entered.await(3,TimeUnit.SECONDS));
  var outcome=expire(handle);assertEquals(AgentState.BUSY,planner.getState());assertTrue(orchestrator.runStatus(handle.executionId()).runtime().cleanupPending());release.countDown();awaitClean(handle.executionId());assertSame(outcome,handle.result().join());assertEquals(0,starts.get());record("budget-4","planner-wait",handle,outcome);
 }
 @Test void budget5SynthesisWaitCannotPublishLateAnswer()throws Exception{
  init(2);register(new PlannerAgent(model){@Override protected String callLlm(String s,String p){return ONE.substring(0,ONE.length()-1)+","+ONE.substring(1);}});register(new AbstractBaseAgent("research-fixture","research",model){@Override public CompletableFuture<String> processTask(Task t){return CompletableFuture.completedFuture("controlled evidence");}});
  var supervisor=new SupervisorAgent(model){@Override protected String callLlm(String s,String p){return block("late synthesis");}};register(supervisor);
  var handle=orchestrator.submit("Summarize the room reservation guidance.",Map.of(),1000L,r->{});assertTrue(entered.await(3,TimeUnit.SECONDS));assertEquals("synthesizing",orchestrator.runStatus(handle.executionId()).report().status());
  var outcome=expire(handle);assertEquals(AgentState.BUSY,supervisor.getState());assertTrue(orchestrator.runStatus(handle.executionId()).runtime().cleanupPending());release.countDown();awaitClean(handle.executionId());assertSame(outcome,handle.result().join());assertNotEquals("late synthesis",outcome.result());record("budget-5","synthesis-wait",handle,outcome);
 }
 @Test void budget3ExpiredWorkerQueueNeverStartsAfterCapacityReturns()throws Exception{
  init(1);planner();register(new ResearchAgent(model){@Override protected String callLlm(String s,String p){return block("first result");}});
  var first=orchestrator.submit("occupy worker",Map.of(),null,r->{});assertTrue(entered.await(3,TimeUnit.SECONDS));
  var planned=new CountDownLatch(1);var second=orchestrator.submit("Explain the policy for a lost ID card.",Map.of(),1000L,r->{if(r.planningTask()!=null&&r.planningTask().status()==TaskStatus.IN_PROGRESS)planned.countDown();});assertTrue(planned.await(3,TimeUnit.SECONDS));
  long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(((Number)orchestrator.runtimeMetrics().get("queuedWorkers")).intValue()==0&&System.nanoTime()<end)Thread.sleep(5);
  assertEquals(1,((Number)orchestrator.runtimeMetrics().get("queuedWorkers")).intValue());var outcome=expire(second);assertEquals(0,((Number)orchestrator.runtimeMetrics().get("queuedWorkers")).intValue());release.countDown();awaitClean(second.executionId());assertSame(outcome,second.result().join());assertTrue(first.result().get(3,TimeUnit.SECONDS).successful());record("budget-3","worker-queue",second,outcome);
 }
}



