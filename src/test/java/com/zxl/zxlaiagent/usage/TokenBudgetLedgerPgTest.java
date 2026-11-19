package com.zxl.zxlaiagent.usage;
import com.zxl.zxlaiagent.security.*;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
class TokenBudgetLedgerPgTest {
 SecurityDatabase db;String tenant;TokenBudgetLedger ledger;
 @BeforeEach void setup(){db=SecurityDatabase.fromEnvironment();tenant="budget-"+UUID.randomUUID();db.tx(c->{try(var s=SecurityDatabase.statement(c,"INSERT INTO tenant(tenant_id) VALUES (?)",tenant)){s.executeUpdate();}return null;});ledger=new TokenBudgetLedger(db,tenant);ledger.createAccount("run",100,2);}
 @AfterEach void clean(){if(db!=null){db.tx(c->{try(var s=SecurityDatabase.statement(c,"DELETE FROM tenant WHERE tenant_id=?",tenant)){s.executeUpdate();}return null;});db.close();}}
 @Test void concurrentInstancesReserveAtomically()throws Exception{
  var pool=Executors.newFixedThreadPool(2);try{var other=new TokenBudgetLedger(db,tenant);var start=new CountDownLatch(1);var results=new ArrayList<Future<Boolean>>();
   for(int i=0;i<2;i++){final String id="call"+i;results.add(pool.submit(()->{start.await();try{other.reserve("run",id,70);return true;}catch(IllegalStateException denied){assertEquals("BUDGET_EXHAUSTED",denied.getMessage());return false;}}));}start.countDown();int count=0;for(var f:results)if(f.get(10,TimeUnit.SECONDS))count++;assertEquals(1,count);assertEquals(70,ledger.account("run").reserved());
  }finally{pool.shutdownNow();}}
 @Test void replaySettlementAndOveragePersistAcrossInstances(){
  ledger.reserve("run","call",60);ledger.reserve("run","call",60);assertThrows(IllegalStateException.class,()->ledger.reserve("run","call",61));assertTrue(ledger.dispatch("run","call"));assertFalse(ledger.dispatch("run","call"));ledger.settle("run","call",120);ledger.settle("run","call",120);assertThrows(IllegalStateException.class,()->ledger.settle("run","call",119));var independentDb=SecurityDatabase.fromEnvironment();var reloaded=new TokenBudgetLedger(independentDb,tenant);assertEquals(120,reloaded.account("run").spent());assertEquals(0,reloaded.account("run").reserved());assertThrows(IllegalStateException.class,()->reloaded.reserve("run","next",1));assertThrows(IllegalStateException.class,()->ledger.releaseUnsent("run","call"));independentDb.close();
 }
 @Test void unknownRetainsReservationAndReleaseRequiresUnsent(){
  ledger.reserve("run","sent",40);ledger.dispatch("run","sent");ledger.unknown("run","sent");assertEquals(40,ledger.account("run").reserved());assertThrows(IllegalStateException.class,()->ledger.releaseUnsent("run","sent"));assertThrows(IllegalStateException.class,()->ledger.settle("run","sent",0));ledger.reserve("run","unsent",20);ledger.releaseUnsent("run","unsent");ledger.releaseUnsent("run","unsent");assertEquals(40,ledger.account("run").reserved());assertEquals(2,ledger.account("run").calls());assertThrows(IllegalStateException.class,()->ledger.reserve("run","third",1));
 }
 @Test void missingScopeCannotReadOrMutateOtherTenant(){var other=new TokenBudgetLedger(db,"other-tenant");assertThrows(IllegalStateException.class,()->other.account("run"));assertThrows(IllegalStateException.class,()->other.reserve("run","call",1));assertThrows(IllegalArgumentException.class,()->ledger.reserve("run","bad",0));}
}



