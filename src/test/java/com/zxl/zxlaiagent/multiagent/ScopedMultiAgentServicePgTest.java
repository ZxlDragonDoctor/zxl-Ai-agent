package com.zxl.zxlaiagent.multiagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zxl.zxlaiagent.multiagent.agents.AbstractBaseAgent;
import com.zxl.zxlaiagent.multiagent.bus.AgentMessageBus;
import com.zxl.zxlaiagent.multiagent.execution.*;
import com.zxl.zxlaiagent.multiagent.model.*;
import com.zxl.zxlaiagent.multiagent.orchestration.OrchestratorAgent;
import com.zxl.zxlaiagent.multiagent.runtime.RuntimeSettings;
import com.zxl.zxlaiagent.multiagent.security.ScopedMultiAgentService;
import com.zxl.zxlaiagent.security.*;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.model.ChatModel;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real PG authority plus the actual orchestrator/runtime; worker outputs are deterministic fixtures. */
class ScopedMultiAgentServicePgTest {
    SecurityDatabase db;AuthenticationService authentication;AuthorizationService authorization;
    OrchestratorAgent orchestrator;ScopedMultiAgentService service;AgentMessageBus bus;
    String a,b,peer,ta,tb;ActorRef actorA,actorB,actorPeer;
    final List<CompletableFuture<String>> pending=new ArrayList<>();
    @BeforeEach void setup(){
        db=SecurityDatabase.pooledFromEnvironment();authentication=new AuthenticationService(db);authorization=new AuthorizationService(db);
        String suffix=UUID.randomUUID().toString();a="multi-a-"+suffix;b="multi-b-"+suffix;peer="multi-peer-"+suffix;ta=a+"-tenant";tb=b+"-tenant";
        authentication.provision(a,a,"synthetic-password",ta,"employee");authentication.provision(b,b,"synthetic-password",tb,"employee");authentication.provision(peer,peer,"synthetic-password",ta,"employee");
        actorA=authentication.login(a,"synthetic-password",ta).actor().ref();actorB=authentication.login(b,"synthetic-password",tb).actor().ref();actorPeer=authentication.login(peer,"synthetic-password",ta).actor().ref();
        start(new RuntimeSettings());
    }
    void start(RuntimeSettings settings){orchestrator=new OrchestratorAgent(mock(ChatModel.class),settings,System::nanoTime);bus=mock(AgentMessageBus.class);orchestrator.init(bus);service=new ScopedMultiAgentService(orchestrator,authorization);}
    void worker(String role,Function<Task,CompletableFuture<String>> work){
        var agent=new AbstractBaseAgent(role,role,mock(ChatModel.class)){public CompletableFuture<String> processTask(Task task){return work.apply(task);}};
        agent.attachMessageBus(bus);agent.requireScopedExecution();orchestrator.registerAgent(agent);
    }
    String plan(Task task){
        var plan=Plan.create(task.getDescription());plan.setId("same-plan-id");var step=Task.create("synthetic task");step.setId("same-task-id");step.setInput(Map.of("capability","supervisor"));plan.addTask(step);
        try{return new ObjectMapper().writeValueAsString(plan);}catch(Exception e){throw new IllegalStateException(e);}
    }
    CompletableFuture<String> pending(){var future=new CompletableFuture<String>();pending.add(future);return future;}
    void await(java.util.function.BooleanSupplier condition)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!condition.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(20);assertTrue(condition.getAsBoolean());}
    @AfterEach void cleanup(){
        pending.forEach(f->f.complete("synthetic late output"));if(orchestrator!=null)orchestrator.shutdown();if(db==null)return;
        try{db.tx(c->{for(String subject:List.of(a,b,peer)){
            try(var s=SecurityDatabase.statement(c,"DELETE FROM authorization_audit WHERE subject_id=?",subject)){s.executeUpdate();}
            try(var s=SecurityDatabase.statement(c,"DELETE FROM subject WHERE subject_id=?",subject)){s.executeUpdate();}
        }for(String tenant:List.of(ta,tb))try(var s=SecurityDatabase.statement(c,"DELETE FROM tenant WHERE tenant_id=?",tenant)){s.executeUpdate();}return null;});}finally{db.close();}
    }
    @Test void currentOwnerScopesStatusReadAndCancelAcrossTenantAndSameTenantPeer()throws Exception{
        worker("planner",task->CompletableFuture.completedFuture(plan(task)));worker("supervisor",task->CompletableFuture.completedFuture(((TaskExecutionContext)task.getInput().get("executionContext")).originalGoal()));clearInvocations(bus);
        String runA=service.submit(actorA,"synthetic A",Map.of(),15000L),runB=service.submit(actorB,"synthetic B",Map.of(),15000L);
        await(()->orchestrator.runStatusScoped(runA).run().report().outcome()!=null&&orchestrator.runStatusScoped(runB).run().report().outcome()!=null);
        assertEquals("synthetic A",service.detail(actorA,runA).run().report().result());assertEquals("synthetic B",service.detail(actorB,runB).run().report().result());
        for(ActorRef stranger:List.of(actorB,actorPeer)){
            assertEquals(404,assertThrows(SecurityFailure.class,()->service.detail(stranger,runA)).status());
            assertEquals(404,assertThrows(SecurityFailure.class,()->service.cancel(stranger,runA)).status());
        }
        assertEquals(List.of(runA),service.status(actorA).runs().stream().map(v->v.run().executionId()).toList());assertTrue(service.status(actorPeer).runs().isEmpty());
        verify(bus,never()).publishEvent(any());
    }
    @Test void cancellationKeepsActualLeaseUntilUnderlyingWorkCleansUp()throws Exception{
        var began=new CountDownLatch(1);var waiting=pending();worker("planner",task->{began.countDown();return waiting;});clearInvocations(bus);
        String run=service.submit(actorA,"synthetic cancellation",Map.of(),15000L);assertTrue(began.await(3,TimeUnit.SECONDS));
        assertEquals("accepted",service.cancel(actorA,run).status());var cancelled=service.detail(actorA,run);
        assertEquals("cancelled",cancelled.run().report().status());assertTrue(cancelled.run().runtime().cleanupPending());
        waiting.complete("late synthetic output");await(()->!service.detail(actorA,run).run().runtime().cleanupPending());
        assertEquals("cancelled",service.detail(actorA,run).run().report().status());verify(bus,never()).publishEvent(any());
    }
    @Test void pgRevocationStopsResultAndProducesNeutralInternalTerminal()throws Exception{
        var began=new CountDownLatch(1);var waiting=pending();worker("planner",task->{began.countDown();return waiting;});clearInvocations(bus);
        String run=service.submit(actorA,"sensitive synthetic goal",Map.of(),15000L);assertTrue(began.await(3,TimeUnit.SECONDS));
        authorization.setMembershipActive(actorA,false);waiting.complete("sensitive late plan");
        await(()->orchestrator.runStatusScoped(run).run().report().outcome()!=null);
        var terminal=orchestrator.runStatusScoped(run).run().report();assertNull(terminal.goal());assertNull(terminal.result());assertTrue(terminal.tasks().isEmpty());
        assertThrows(SecurityFailure.class,()->service.detail(actorA,run));assertTrue(service.status(actorB).runs().isEmpty());verify(bus,never()).publishEvent(any());
    }
    @Test void capacityFailureRemovesOnlyItsUnstartedOwner()throws Exception{
        orchestrator.shutdown();var settings=new RuntimeSettings();settings.setConcurrentRuns(1);settings.setRunQueueCapacity(1);settings.setRegistryCapacity(2);start(settings);
        var began=new CountDownLatch(1);var waiting=pending();worker("planner",task->{began.countDown();return waiting;});
        String first=service.submit(actorA,"first",Map.of(),15000L);assertTrue(began.await(3,TimeUnit.SECONDS));String second=service.submit(actorA,"second",Map.of(),15000L);
        assertThrows(WorkflowException.class,()->service.submit(actorA,"third",Map.of(),15000L));
        assertEquals(Set.of(first,second),new HashSet<>(authorization.ownedResourceIds(actorA,"run")));
        service.cancel(actorA,first);service.cancel(actorA,second);waiting.complete("late output");
    }
}
