package com.zxl.zxlaiagent.security;

import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AuthorizedRunRegistrationPgTest {
    SecurityDatabase db;AuthenticationService authentication;AuthorizationService authorization;
    String subject,tenant;ActorRef actor;
    @BeforeEach void setup(){
        db=SecurityDatabase.pooledFromEnvironment();authentication=new AuthenticationService(db);authorization=new AuthorizationService(db);
        subject="run-register-"+UUID.randomUUID();tenant=subject+"-tenant";
        authentication.provision(subject,subject,"synthetic-password",tenant,"employee");
        actor=authentication.login(subject,"synthetic-password",tenant).actor().ref();
    }
    @AfterEach void cleanup(){if(db==null)return;try{db.tx(c->{
        try(var s=SecurityDatabase.statement(c,"DELETE FROM authorization_audit WHERE subject_id=?",subject)){s.executeUpdate();}
        try(var s=SecurityDatabase.statement(c,"DELETE FROM subject WHERE subject_id=?",subject)){s.executeUpdate();}
        try(var s=SecurityDatabase.statement(c,"DELETE FROM tenant WHERE tenant_id=?",tenant)){s.executeUpdate();}return null;
    });}finally{db.close();}}
    List<AuthorizationService.Permission> permissions(){return List.of(new AuthorizationService.Permission("agent.execute",null),new AuthorizationService.Permission("knowledge.read",null));}
    int count(String table,String run){return db.tx(c->{
        String sql=table.equals("owner")?"SELECT count(*) FROM resource_owner WHERE resource_type='run' AND resource_id=?":"SELECT count(*) FROM authorization_audit WHERE run_id=?";
        try(var s=SecurityDatabase.statement(c,sql,run);var r=s.executeQuery()){r.next();return r.getInt(1);}
    });}
    @Test void registrationAndCurrentDecisionsCommitTogether(){
        String run=UUID.randomUUID().toString();var resource=new ResourceRef("run",run);
        authorization.registerResourceAuthorized(actor,resource,permissions(),run);
        assertEquals(1,count("owner",run));assertEquals(2,count("audit",run));
        authorization.requireOwned(actor,resource,Phase.HISTORY_READ);
        assertThrows(RuntimeException.class,()->authorization.registerResourceAuthorized(actor,resource,permissions(),run));
        // requireOwned has its own audit with no run_id; the two registration attempts each add two Decisions.
        assertEquals(1,count("owner",run));assertEquals(4,count("audit",run));
    }
    @Test void revokedRegistrationCommitsDenialWithoutCreatingOwner(){
        String run=UUID.randomUUID().toString();authorization.setMembershipActive(actor,false);
        assertThrows(SecurityFailure.class,()->authorization.registerResourceAuthorized(actor,new ResourceRef("run",run),permissions(),run));
        assertEquals(0,count("owner",run));assertEquals(2,count("audit",run));
    }
    @Test void unstartedCleanupIsBoundToExactOwnerEvenAfterSessionRevocation(){
        String run=UUID.randomUUID().toString();authorization.registerResourceAuthorized(actor,new ResourceRef("run",run),permissions(),run);
        authorization.discardUnstartedRun(new ActorRef("other",tenant,actor.sessionId(),1),run);assertEquals(1,count("owner",run));
        authentication.revoke(actor);authorization.discardUnstartedRun(actor,run);assertEquals(0,count("owner",run));
        assertEquals(2,count("audit",run));
    }
    @Test void candidateOwnerLookupIsBoundedAndDoesNotScanHistoricalOwners(){
        String retained=UUID.randomUUID().toString(),historical=UUID.randomUUID().toString();
        for(String id:List.of(retained,historical))authorization.registerResourceAuthorized(actor,new ResourceRef("run",id),permissions(),id);
        assertEquals(List.of(retained),authorization.ownedResourceIds(actor,"run",List.of(retained,UUID.randomUUID().toString())));
        assertThrows(IllegalArgumentException.class,()->authorization.ownedResourceIds(actor,"run",java.util.stream.IntStream.range(0,201).mapToObj(i->"synthetic-"+i).toList()));
        authentication.revoke(actor);assertThrows(SecurityFailure.class,()->authorization.ownedResourceIds(actor,"run",List.of()));
    }
}
