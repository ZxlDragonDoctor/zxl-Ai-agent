package com.zxl.zxlaiagent.usage;
import com.zxl.zxlaiagent.security.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
class TenantPeriodBudgetPgTest {
 @Test void releaseOverageAndRestartRetainBothLayers() {
  try(var db=SecurityDatabase.fromEnvironment()) {
   String tenant="period-state-"+UUID.randomUUID();
   db.tx(c->{try(var q=SecurityDatabase.statement(c,"INSERT INTO tenant(tenant_id) VALUES (?)",tenant)){q.executeUpdate();}return null;});
   try {
    var actor=new ActorRef("employee",tenant,"fixture",1);var policy=new ScopedModelBudgetFactory.Policy(true,100,10,40,100,10);
    var factory=new ScopedModelBudgetFactory(db,policy);String run=UUID.randomUUID().toString();factory.initialize(actor,run);
    var ledger=new TokenBudgetLedger(db,tenant);ledger.reserve(run,"release",40);ledger.releaseUnsent(run,"release");ledger.releaseUnsent(run,"release");
    assertEquals(0,ledger.period(run).reserved());assertEquals(1,ledger.period(run).calls());
    ledger.reserve(run,"overage",40);ledger.dispatch(run,"overage");ledger.settle(run,"overage",120);
    String next=UUID.randomUUID().toString();factory.initialize(actor,next);
    assertThrows(IllegalStateException.class,()->ledger.reserve(next,"blocked",1));assertEquals(0,ledger.account(next).calls());
    try(var reopened=SecurityDatabase.fromEnvironment()){assertEquals(120,new TokenBudgetLedger(reopened,tenant).period(next).spent());}
    String small=UUID.randomUUID().toString();new ScopedModelBudgetFactory(db,new ScopedModelBudgetFactory.Policy(true,20,1,10,100,10)).initialize(actor,small);
    assertThrows(IllegalStateException.class,()->ledger.reserve(small,"run-denied",21));assertEquals(2,ledger.period(small).calls());
   }finally{db.tx(c->{try(var q=SecurityDatabase.statement(c,"DELETE FROM tenant WHERE tenant_id=?",tenant)){q.executeUpdate();}return null;});}
  }
 }
 @Test void runDenialAndPeriodCallCapAreIsolatedPerTenant() {
  try(var db=SecurityDatabase.fromEnvironment()) {
   String first="period-a-"+UUID.randomUUID(),second="period-b-"+UUID.randomUUID();
   for(String tenant:List.of(first,second))db.tx(c->{try(var q=SecurityDatabase.statement(c,"INSERT INTO tenant(tenant_id) VALUES (?)",tenant)){q.executeUpdate();}return null;});
   try {
    var factory=new ScopedModelBudgetFactory(db,new ScopedModelBudgetFactory.Policy(true,20,10,10,100,1));
    String a=UUID.randomUUID().toString(),b=UUID.randomUUID().toString(),other=UUID.randomUUID().toString();
    var actor=new ActorRef("employee",first,"fixture",1);factory.initialize(actor,a);factory.initialize(actor,b);factory.initialize(new ActorRef("employee",second,"fixture",1),other);
    var ledger=new TokenBudgetLedger(db,first);var independent=new TokenBudgetLedger(db,second);
    var before=ledger.period(a);assertEquals("BUDGET_EXHAUSTED",assertThrows(IllegalStateException.class,()->ledger.reserve(a,"too-large",21)).getMessage());assertEquals(before,ledger.period(a));
    ledger.reserve(a,"accepted",10);ledger.releaseUnsent(a,"accepted");
    assertEquals("TENANT_BUDGET_EXHAUSTED",assertThrows(IllegalStateException.class,()->ledger.reserve(b,"call-cap",10)).getMessage());
    assertEquals(0,ledger.account(b).calls());assertEquals(1,ledger.period(a).calls());
    independent.reserve(other,"own",10);assertEquals(10,independent.period(other).reserved());assertEquals(0,ledger.period(a).reserved());
   }finally{for(String tenant:List.of(first,second))db.tx(c->{try(var q=SecurityDatabase.statement(c,"DELETE FROM tenant WHERE tenant_id=?",tenant)){q.executeUpdate();}return null;});}
  }
 }

 @Test void sharedPeriodIsAtomicAndOriginalDaySurvivesRollover() throws Exception {
  try(var db=SecurityDatabase.fromEnvironment()) {
   String tenant="period-"+UUID.randomUUID();
   db.tx(c->{try(var s=SecurityDatabase.statement(c,"INSERT INTO tenant(tenant_id) VALUES (?)",tenant)){s.executeUpdate();}return null;});
   try {
    var actor=new ActorRef("employee",tenant,"fixture",1); // trusted fixture identity
    var policy=new ScopedModelBudgetFactory.Policy(true,100,10,60,100,10);
    var first=new ScopedModelBudgetFactory(db,policy,null,Clock.fixed(Instant.parse("2026-11-22T23:59:00Z"),ZoneOffset.UTC));
    String a=UUID.randomUUID().toString(),b=UUID.randomUUID().toString();first.initialize(actor,a);first.initialize(actor,b);
    var ledger=new TokenBudgetLedger(db,tenant);
    String legacy=UUID.randomUUID().toString();ledger.createAccount(legacy,100,10);ledger.reserve(legacy,"pre-v9",10);
    assertThrows(IllegalStateException.class,()->first.initialize(actor,legacy));
    var executor=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
    try {
     var futures=new ArrayList<Future<Boolean>>();
     for(String run:List.of(a,b))futures.add(executor.submit(()->{start.await();try{ledger.reserve(run,"one",60);return true;}catch(IllegalStateException ex){assertEquals("TENANT_BUDGET_EXHAUSTED",ex.getMessage());return false;}}));
     start.countDown();int accepted=0;for(var f:futures)if(f.get(10,TimeUnit.SECONDS))accepted++;assertEquals(1,accepted);
    }finally{executor.shutdownNow();}
    assertEquals(60,ledger.period(a).reserved());
    String winner=ledger.account(a).reserved()>0?a:b;
    var next=new ScopedModelBudgetFactory(db,policy,null,Clock.fixed(Instant.parse("2026-11-23T00:01:00Z"),ZoneOffset.UTC));
    next.initialize(actor,winner);String newRun=UUID.randomUUID().toString();next.initialize(actor,newRun);
    assertEquals(0,ledger.period(newRun).reserved());assertEquals(60,ledger.period(winner).reserved());
    ledger.dispatch(winner,"one");ledger.settle(winner,"one",30);ledger.settle(winner,"one",30);
    assertEquals(30,ledger.period(winner).spent());assertEquals(0,ledger.period(winner).reserved());
    ledger.reserve(newRun,"unknown",60);ledger.dispatch(newRun,"unknown");ledger.unknown(newRun,"unknown");
    assertEquals(60,ledger.period(newRun).reserved());assertThrows(IllegalStateException.class,()->ledger.reserve(newRun,"denied",60));
    assertEquals(1,ledger.account(newRun).calls());
   }finally{db.tx(c->{try(var s=SecurityDatabase.statement(c,"DELETE FROM tenant WHERE tenant_id=?",tenant)){s.executeUpdate();}return null;});}
  }
 }
}
