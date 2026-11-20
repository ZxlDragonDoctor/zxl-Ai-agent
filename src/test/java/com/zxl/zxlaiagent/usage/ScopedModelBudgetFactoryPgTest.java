package com.zxl.zxlaiagent.usage;

import com.zxl.zxlaiagent.security.*;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ScopedModelBudgetFactoryPgTest {
 @Test void trustedRunAccountIsSharedAndNeverResetByLaterCalls() {
  try(var db=SecurityDatabase.fromEnvironment()) {
   String tenant="usage-wiring-"+UUID.randomUUID(),run=UUID.randomUUID().toString();
   var auth=new AuthenticationService(db);auth.provision(tenant,tenant,"synthetic-password",tenant,"employee");
   var actor=auth.login(tenant,"synthetic-password",tenant).actor().ref();
   try {
    var factory=new ScopedModelBudgetFactory(db,new ScopedModelBudgetFactory.Policy(true,100,3,60));
    factory.initialize(actor,run);var ledger=new TokenBudgetLedger(db,tenant);ledger.reserve(run,"first",60);
    assertNotNull(factory.call(actor,run));
    assertNotEquals(((TokenCallBudget)factory.call(actor,run)).callId(),((TokenCallBudget)factory.call(actor,run)).callId());
    assertThrows(IllegalStateException.class,()->new ScopedModelBudgetFactory(db,new ScopedModelBudgetFactory.Policy(true,200,3,60)).initialize(actor,run));
    assertEquals(60,ledger.account(run).reserved());
    assertThrows(IllegalStateException.class,()->ledger.reserve(run,"second",60));
    assertThrows(IllegalArgumentException.class,()->factory.call(actor,null));
    assertNull(new ScopedModelBudgetFactory(db,new ScopedModelBudgetFactory.Policy(false,100,3,60)).call(actor,run));
   } finally {db.tx(c->{try(var s=SecurityDatabase.statement(c,"DELETE FROM authorization_audit WHERE tenant_id=?",tenant)){s.executeUpdate();}try(var s=SecurityDatabase.statement(c,"DELETE FROM tenant WHERE tenant_id=?",tenant)){s.executeUpdate();}try(var s=SecurityDatabase.statement(c,"DELETE FROM subject WHERE subject_id=?",tenant)){s.executeUpdate();}return null;});}
  }
 }
}
