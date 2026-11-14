package com.zxl.zxlaiagent.multiagent;

import com.zxl.zxlaiagent.multiagent.security.ScopedMultiAgentService;
import com.zxl.zxlaiagent.multiagent.orchestration.OrchestratorAgent;
import com.zxl.zxlaiagent.security.*;
import com.zxl.zxlaiagent.security.execution.OperationBoundary;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScopedMultiAgentServiceTest {
    final ActorRef actor=new ActorRef("synthetic","tenant","session",1);
    final AuthorizationService authorization=mock(AuthorizationService.class);
    final OrchestratorAgent orchestrator=mock(OrchestratorAgent.class);
    final SecurityDatabase db=mock(SecurityDatabase.class);
    final ScopedMultiAgentService service=new ScopedMultiAgentService(orchestrator,authorization,db);
    final String id=UUID.randomUUID().toString();
    @BeforeEach void gates(){
        when(authorization.gateAll(any(),anyList(),any(),any(),any())).thenAnswer(i->((Supplier<?>)i.getArgument(4)).get());
        when(authorization.decide(any(),anyString(),any(),any(),any())).thenReturn(new AuthorizationDecision("synthetic","v1",true,"ALLOWED"));
    }
    @Test void cancellationUsesWritePolicyWithoutRequiringReadDisclosure(){
        doThrow(new SecurityFailure("ACTION_DENIED",403)).when(authorization).requireOwned(any(),any(),any());
        when(orchestrator.cancelScoped(eq(id),any())).thenReturn(new OrchestratorAgent.CancelReceipt("accepted",null));
        assertEquals("accepted",service.cancel(actor,id).status());
        verify(authorization).decide(actor,"run.writeOwn",new ResourceRef("run",id),Phase.ENTRY,id);
    }
    @Test void foreignCancellationIsHiddenBeforeRegistryAccess(){
        when(authorization.decide(eq(actor),eq("run.writeOwn"),any(),any(),any())).thenReturn(new AuthorizationDecision("synthetic","v1",false,"RESOURCE_UNAVAILABLE"));
        assertEquals(404,assertThrows(SecurityFailure.class,()->service.cancel(actor,id)).status());verifyNoInteractions(orchestrator);
    }
    @Test void submitRegistersTrustedOwnerBeforeReturningContext(){
        when(orchestrator.submitScoped(anyString(),anyMap(),any(),any())).thenAnswer(i->{
            Function<String,OrchestratorAgent.ScopedSubmission> factory=i.getArgument(3);var scope=factory.apply(id);
            verify(authorization).registerResourceAuthorized(eq(actor),eq(new ResourceRef("run",id)),anyList(),eq(id));
            assertEquals(actor,scope.context().actor());assertEquals(id,scope.context().runId());
            scope.boundary().check(Phase.MODEL_DISPATCH);
            return new OrchestratorAgent.RunHandle(id,new CompletableFuture<>());
        });
        assertEquals(id,service.submit(actor,"synthetic",Map.of(),5000L));
        verify(authorization).gateAll(eq(actor),eq(List.of(new AuthorizationService.Permission("agent.execute",null),new AuthorizationService.Permission("knowledge.read",null),new AuthorizationService.Permission("run.readOwn",new ResourceRef("run",id)))),eq(Phase.MODEL_DISPATCH),eq(id),any());
    }
    @Test void rejectedRegistryRegistrationCompensatesOnlyTheNewOwner(){
        when(orchestrator.submitScoped(anyString(),anyMap(),any(),any())).thenAnswer(i->{
            ((Function<String,OrchestratorAgent.ScopedSubmission>)i.getArgument(3)).apply(id);throw new IllegalStateException("synthetic capacity failure");
        });
        assertThrows(IllegalStateException.class,()->service.submit(actor,"synthetic",Map.of(),5000L));
        verify(authorization).discardUnstartedRun(actor,id);
    }
    @Test void foreignRunIsHiddenBeforeAnyRegistryRead(){
        doThrow(new SecurityFailure("RESOURCE_UNAVAILABLE",403)).when(authorization).requireOwned(eq(actor),any(),any());
        assertEquals(404,assertThrows(SecurityFailure.class,()->service.detail(actor,id)).status());verifyNoInteractions(orchestrator);
    }
    @Test void authorizedMissingOrExpiredRegistryEntryIsAlso404(){
        when(db.tx(any())).thenThrow(new SecurityFailure("NOT_FOUND",404));
        assertEquals(404,assertThrows(SecurityFailure.class,()->service.detail(actor,id)).status());
        verify(orchestrator).runStatusScoped(id);
    }
    @Test void cancelAcknowledgementCannotIncludeSensitiveReport(){
        when(orchestrator.cancelScoped(eq(id),any())).thenAnswer(i->{
            ((OperationBoundary)i.getArgument(1)).start(Phase.ENTRY,()->null);return new OrchestratorAgent.CancelReceipt("accepted",null);
        });
        var acknowledgement=service.cancel(actor,id);assertEquals(id,acknowledgement.executionId());assertEquals("accepted",acknowledgement.status());
        assertEquals(List.of("executionId","status"),Arrays.stream(acknowledgement.getClass().getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).toList());
        verify(authorization).gateAll(eq(actor),eq(List.of(new AuthorizationService.Permission("agent.execute",null),new AuthorizationService.Permission("run.writeOwn",new ResourceRef("run",id)))),eq(Phase.ENTRY),eq(id),any());
    }
    @Test void workspaceNeverQueriesLegacyGlobalObserver(){
        when(orchestrator.retainedScopedRunIds(200)).thenReturn(List.of(id));
        when(authorization.ownedResourceIds(actor,"run",List.of(id))).thenReturn(List.of(id));
        when(db.tx(any())).thenReturn(new com.zxl.zxlaiagent.security.execution.OperationBoundary.SqlOutcome<>(List.of(),null));
        assertTrue(service.status(actor).runs().isEmpty());verify(orchestrator).runStatusScoped(id);verify(orchestrator,never()).getStatus();
        verify(authorization,never()).ownedResourceIds(actor,"run");
    }
}
