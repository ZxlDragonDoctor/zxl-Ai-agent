package com.zxl.zxlaiagent.security;

import com.zxl.zxlaiagent.multiagent.runtime.*;
import com.zxl.zxlaiagent.multiagent.execution.WorkflowRun;
import com.zxl.zxlaiagent.multiagent.model.WorkflowException;
import com.zxl.zxlaiagent.security.execution.OperationBoundary;
import com.zxl.zxlaiagent.usage.*;
import org.junit.jupiter.api.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class RejectedRunBudgetCleanupPgTest {
 SecurityDatabase db;AuthorizationService authorization;ScopedModelBudgetFactory budgets;ActorRef actor;
 @BeforeEach void setup(){db=SecurityDatabase.fromEnvironment();String id=UUID.randomUUID().toString();actor=new ActorRef(id,"cleanup-"+id,"revoked-session",1);authorization=new AuthorizationService(db);
  sql("INSERT INTO tenant(tenant_id) VALUES (?)",actor.tenantId());sql("INSERT INTO subject(subject_id,login_name,password_hash) VALUES (?,?,?)",id,id,"synthetic");sql("INSERT INTO membership(tenant_id,subject_id,role) VALUES (?,?,'employee')",actor.tenantId(),id);
  budgets=new ScopedModelBudgetFactory(db,new ScopedModelBudgetFactory.Policy(true,100,8,10));
 }
 @AfterEach void clean(){if(db!=null){sql("DELETE FROM tenant WHERE tenant_id=?",actor.tenantId());sql("DELETE FROM subject WHERE subject_id=?",actor.subjectId());db.close();}}
 String create(boolean budget){return create(UUID.randomUUID().toString(),budget);}
 String create(String id,boolean budget){sql("INSERT INTO resource_owner(resource_type,resource_id,tenant_id,subject_id) VALUES ('run',?,?,?)",id,actor.tenantId(),actor.subjectId());if(budget)budgets.initialize(actor,id);return id;}
 long count(String table){return db.tx(c->{try(var s=SecurityDatabase.statement(c,"SELECT count(*) FROM "+table+" WHERE tenant_id=?",actor.tenantId());var r=s.executeQuery()){r.next();return r.getLong(1);}});}
 void sql(String query,Object...args){db.tx(c->{try(var s=SecurityDatabase.statement(c,query,args)){s.executeUpdate();}return null;});}
 @Test void repeatedActualRegistryRejectionsDoNotAccumulateAccountsOrOwners(){
  var settings=new RuntimeSettings();settings.setTenantRunCapacity(1);var registry=new RunRegistry(settings);
  var run=new WorkflowRun("occupy",r->{});var context=new TrustedRunContext(actor,run.id,new PrivateRunEvents());registry.register(run,new ExecutionControl(0,Duration.ofSeconds(10),System::nanoTime),0,OperationBoundary.unrestricted(),context);
  for(int i=0;i<5;i++){
   var rejected=new WorkflowRun("rejected",r->{});String id=create(rejected.id,true);
   assertEquals("AGENT_TENANT_CAPACITY_EXCEEDED",assertThrows(WorkflowException.class,()->registry.register(rejected,new ExecutionControl(0,Duration.ofSeconds(10),System::nanoTime),0,OperationBoundary.unrestricted(),new TrustedRunContext(actor,rejected.id,new PrivateRunEvents()))).getCode());
   authorization.discardUnstartedRun(actor,id);assertEquals(0,count("token_budget_account"));assertEquals(0,count("resource_owner"));
  }
  assertEquals(1,count("token_budget_period"),"shared period is intentionally retained");
 }
 @Test void exactOwnerRequiredAndLegacyAccountlessCompensationStillWorks(){
  String id=create(true);authorization.discardUnstartedRun(new ActorRef("wrong",actor.tenantId(),"s",1),id);authorization.discardUnstartedRun(new ActorRef(actor.subjectId(),"wrong","s",1),id);
  assertEquals(1,count("token_budget_account"));assertEquals(1,count("resource_owner"));authorization.discardUnstartedRun(actor,id);assertEquals(0,count("token_budget_account"));
  String legacy=create(false);authorization.discardUnstartedRun(actor,legacy);assertEquals(0,count("resource_owner"));
 }
 @Test void everyCallStateIncludingReleasedAuditPreventsDeletion(){
  var ledger=new TokenBudgetLedger(db,actor.tenantId());
  for(String state:List.of("RESERVED","DISPATCHED","UNKNOWN","SETTLED","RELEASED")){
   String id=create(true);ledger.reserve(id,"call",10);
   if(!state.equals("RESERVED")&&!state.equals("RELEASED"))assertTrue(ledger.dispatch(id,"call"));
   if(state.equals("UNKNOWN"))ledger.unknown(id,"call");if(state.equals("SETTLED"))ledger.settle(id,"call",3);if(state.equals("RELEASED"))ledger.releaseUnsent(id,"call");
   authorization.discardUnstartedRun(actor,id);
  }
  assertEquals(5,count("token_budget_account"));assertEquals(5,count("token_budget_call"));assertEquals(5,count("resource_owner"));
 }
 @Test void durableRunAndNonzeroCountersOrEvenZeroCounterCallPreventDeletion(){
  String id=create(true);db.tx(c->{try(var s=SecurityDatabase.statement(c,"INSERT INTO durable_agent_run(tenant_id,run_id,latest_sequence) VALUES (?,?::uuid,1)",actor.tenantId(),id)){s.executeUpdate();}try(var s=SecurityDatabase.statement(c,"INSERT INTO durable_agent_run_event(tenant_id,run_id,event_sequence,status,public_snapshot) VALUES (?,?::uuid,1,'queued','{}')",actor.tenantId(),id)){s.executeUpdate();}return null;});authorization.discardUnstartedRun(actor,id);
  for(String column:List.of("reserved_tokens","spent_tokens","accepted_calls")){String next=create(true);sql("UPDATE token_budget_account SET "+column+"=1 WHERE tenant_id=? AND account_id=?",actor.tenantId(),next);authorization.discardUnstartedRun(actor,next);}
  String raw=create(true);sql("INSERT INTO token_budget_call(tenant_id,account_id,call_id,estimate,state) VALUES (?,?,'audit',1,'RELEASED')",actor.tenantId(),raw);authorization.discardUnstartedRun(actor,raw);
  assertEquals(5,count("token_budget_account"));assertEquals(5,count("resource_owner"));
 }
 @Test void failureDeletingOwnerRollsBackBothDeletes(){
  String id=create(true),suffix=UUID.randomUUID().toString().replace("-",""),fn="cleanup_"+suffix;
  sql("CREATE FUNCTION lesson17."+fn+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF OLD.tenant_id='"+actor.tenantId()+"' THEN RAISE EXCEPTION 'synthetic cleanup failure'; END IF; RETURN OLD; END $$");
  sql("CREATE TRIGGER "+fn+" BEFORE DELETE ON lesson17.resource_owner FOR EACH ROW EXECUTE FUNCTION lesson17."+fn+"()");
  try{assertThrows(RuntimeException.class,()->authorization.discardUnstartedRun(actor,id));assertEquals(1,count("token_budget_account"));assertEquals(1,count("resource_owner"));}
  finally{sql("DROP TRIGGER "+fn+" ON lesson17.resource_owner");sql("DROP FUNCTION lesson17."+fn+"()");}
 }
 @Test void concurrentReserveAndCleanupNeverDeleteAReservedCall()throws Exception{
  var pool=Executors.newFixedThreadPool(2);try{
   for(int i=0;i<8;i++){String id=create(true);var start=new CountDownLatch(1);var ledger=new TokenBudgetLedger(db,actor.tenantId());
    var reserve=pool.submit(()->{assertTrue(start.await(3,TimeUnit.SECONDS));try{ledger.reserve(id,"race",10);return true;}catch(IllegalStateException e){assertEquals("ACCOUNT_NOT_FOUND",e.getMessage());return false;}});
    var cleanup=pool.submit(()->{assertTrue(start.await(3,TimeUnit.SECONDS));authorization.discardUnstartedRun(actor,id);return true;});start.countDown();boolean reserved=reserve.get(10,TimeUnit.SECONDS);assertTrue(cleanup.get(10,TimeUnit.SECONDS));
    if(reserved){assertEquals(10,ledger.account(id).reserved());assertEquals(1,ledger.account(id).calls());}else assertThrows(IllegalStateException.class,()->ledger.account(id));
   }
  }finally{pool.shutdownNow();}
 }
 @Test void ownerGateCanReserveWhileCleanupWaitsWithoutAccountOwnerLockInversion()throws Exception{
  String id=create(true);var pool=Executors.newSingleThreadExecutor();var cleanup=new java.util.concurrent.atomic.AtomicReference<Future<?>>();
  try{
   db.tx(c->{
    try(var s=SecurityDatabase.statement(c,"SELECT 1 FROM resource_owner WHERE tenant_id=? AND resource_id=? FOR UPDATE",actor.tenantId(),id);var r=s.executeQuery()){assertTrue(r.next());}
    cleanup.set(pool.submit(()->authorization.discardUnstartedRun(actor,id)));
    long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);boolean blocked=false;
    while(System.nanoTime()<end){
     blocked=db.tx(other->{try(var s=SecurityDatabase.statement(other,"SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND wait_event_type='Lock' AND query LIKE '%FROM resource_owner%')");var r=s.executeQuery()){r.next();return r.getBoolean(1);}});
     if(blocked)break;Thread.yield();
    }
    assertTrue(blocked,"cleanup must reach the held owner gate before reservation");
    new TokenBudgetLedger(db,actor.tenantId()).reserve(id,"gate-reserve",10);
    return null;
   });
   cleanup.get().get(10,TimeUnit.SECONDS);assertEquals(10,new TokenBudgetLedger(db,actor.tenantId()).account(id).reserved());assertEquals(1,count("resource_owner"));
  }finally{pool.shutdownNow();}
 }
}
