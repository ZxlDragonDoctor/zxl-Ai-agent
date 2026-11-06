package com.zxl.zxlaiagent.research;

import com.zxl.zxlaiagent.security.*;
import com.zxl.zxlaiagent.security.execution.*;
import com.zxl.zxlaiagent.multiagent.model.*;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real PG identity/owner/revocation and actual async Agent; model/tool responses are synthetic. */
class ScopedResearchAgentPgTest {
    SecurityDatabase db;AuthenticationService authentication;AuthorizationService authorization;
    String subjectA,subjectB,tenantA,tenantB;ActorRef actorA,actorB;
    @BeforeEach void setup(){
        db=SecurityDatabase.pooledFromEnvironment();authentication=new AuthenticationService(db);authorization=new AuthorizationService(db);
        String suffix=UUID.randomUUID().toString();subjectA="research-a-"+suffix;subjectB="research-b-"+suffix;
        tenantA=subjectA+"-tenant";tenantB=subjectB+"-tenant";
        authentication.provision(subjectA,subjectA,"synthetic-password",tenantA,"employee");
        authentication.provision(subjectB,subjectB,"synthetic-password",tenantB,"employee");
        actorA=authentication.login(subjectA,"synthetic-password",tenantA).actor().ref();
        actorB=authentication.login(subjectB,"synthetic-password",tenantB).actor().ref();
    }
    @AfterEach void cleanup(){
        if(db==null)return;
        try{db.tx(c->{
            for(String subject:List.of(subjectA,subjectB)){
                try(var s=SecurityDatabase.statement(c,"DELETE FROM authorization_audit WHERE subject_id=?",subject)){s.executeUpdate();}
                try(var s=SecurityDatabase.statement(c,"DELETE FROM subject WHERE subject_id=?",subject)){s.executeUpdate();}
            }
            for(String tenant:List.of(tenantA,tenantB))try(var s=SecurityDatabase.statement(c,"DELETE FROM tenant WHERE tenant_id=?",tenant)){s.executeUpdate();}
            return null;
        });}finally{db.close();}
    }
    List<AuthorizationService.Permission> permissions(String runId){
        return List.of(new AuthorizationService.Permission("agent.execute",null),new AuthorizationService.Permission("knowledge.read",null),
                new AuthorizationService.Permission("run.readOwn",new ResourceRef("run",runId)));
    }
    @Test void actualOwnerIsRequiredBeforeAnyPrivateEventOrModelWork() throws Exception {
        for(ActorRef actor:List.of(actorA,actorB)){
            // The boundary captures the server-created run id after the fixture constructs it.
            var target=new java.util.concurrent.atomic.AtomicReference<OperationBoundary>();
            OperationBoundary boundary=new OperationBoundary(){
                public void check(Phase p){target.get().check(p);}
                public <T>T start(Phase p,java.util.function.Supplier<T> work){return target.get().start(p,work);}
            };
            try(var fixture=new ScopedResearchAgentTest.Fixture(boundary,actor)){
                authorization.registerResource(actorA,new ResourceRef("run",fixture.run.id));
                target.set(new ActorBoundary(authorization,actor,permissions(fixture.run.id),fixture.run.id));
                when(fixture.factory.create(eq(actor),eq(fixture.run.id),anyList(),any(OperationBoundary.class))).thenReturn(fixture.runner());
                if(actor==actorA){
                    assertTrue(fixture.agent().executeTask(fixture.task).get(5,TimeUnit.SECONDS).contains("[C1]"));
                    assertEquals(2,fixture.task.snapshot().modelCalls());assertEquals(1,fixture.toolCalls.get());
                }else{
                    assertThrows(ExecutionException.class,()->fixture.agent().executeTask(fixture.task).get(5,TimeUnit.SECONDS));
                    assertTrue(fixture.events().isEmpty());assertEquals(0,fixture.toolCalls.get());assertEquals(0,fixture.task.snapshot().modelCalls());
                    verifyNoInteractions(fixture.factory);
                }
                verifyNoInteractions(fixture.global);verifyNoInteractions(fixture.legacy);
            }
        }
    }
    @Test void pgRevocationAfterAnswerStopsTaskCompletionAndRetainsDeniedAudit() throws Exception {
        var target=new java.util.concurrent.atomic.AtomicReference<OperationBoundary>();
        OperationBoundary boundary=new OperationBoundary(){
            public void check(Phase p){target.get().check(p);}
            public <T>T start(Phase p,java.util.function.Supplier<T> work){return target.get().start(p,work);}
        };
        try(var fixture=new ScopedResearchAgentTest.Fixture(boundary,actorA)){
            authorization.registerResource(actorA,new ResourceRef("run",fixture.run.id));
            target.set(new ActorBoundary(authorization,actorA,permissions(fixture.run.id),fixture.run.id));
            when(fixture.factory.create(eq(actorA),eq(fixture.run.id),anyList(),any(OperationBoundary.class))).thenReturn(fixture.runner(()->authorization.setMembershipActive(actorA,false)));
            assertThrows(ExecutionException.class,()->fixture.agent().executeTask(fixture.task).get(5,TimeUnit.SECONDS));
            assertNotEquals(TaskStatus.COMPLETED,fixture.task.snapshot().status());
            assertTrue(fixture.events().stream().noneMatch(e->e.getType()==EventType.TASK_COMPLETED));
            verifyNoInteractions(fixture.global);
            int denied=db.tx(c->{try(var s=SecurityDatabase.statement(c,"SELECT count(*) FROM authorization_audit WHERE subject_id=? AND run_id=? AND allowed=false",subjectA,fixture.run.id);var r=s.executeQuery()){r.next();return r.getInt(1);}});
            assertTrue(denied>0);
        }
    }
}
