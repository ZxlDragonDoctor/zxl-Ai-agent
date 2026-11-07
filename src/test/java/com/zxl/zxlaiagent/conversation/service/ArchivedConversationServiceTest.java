package com.zxl.zxlaiagent.conversation.service;

import com.zxl.zxlaiagent.conversation.ConversationException;
import com.zxl.zxlaiagent.conversation.agent.*;
import com.zxl.zxlaiagent.conversation.model.*;
import com.zxl.zxlaiagent.conversation.repository.ConversationRepository;
import com.zxl.zxlaiagent.security.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ArchivedConversationServiceTest {
    final ActorRef actor=new ActorRef("archive-owner","archive-tenant","archive-session",1);
    final UUID id=UUID.randomUUID();
    final String digest="a".repeat(64);
    final ConversationRepository repository=mock(ConversationRepository.class);
    final AuthorizationService authorization=mock(AuthorizationService.class);
    final ConversationAgentRegistry agents=mock(ConversationAgentRegistry.class);
    final ConversationRunGuard guard=new ConversationRunGuard();
    ConversationService service(){var s=new ConversationService(repository,guard,agents);s.setAuthorizationService(authorization);return s;}
    Conversation oldRunning(){var at=Instant.parse("2026-01-01T00:00:00Z");return new Conversation(id,"synthetic archive",at,at,0,List.of(),List.of(RunRecord.started(UUID.randomUUID(),at)),"retired-provider");}
    void allowReads(){
        when(authorization.legacyConversationDigest(actor,id)).thenReturn(digest);
        doAnswer(c->((Supplier<?>)c.getArgument(5)).get()).when(authorization).gate(eq(actor),eq("conversation.readOwn"),any(),eq(Phase.HISTORY_READ),isNull(),any());
        when(repository.findArchived(id,digest)).thenReturn(Optional.of(oldRunning()));
    }
    @Test void archivedRunningIsReadableWithoutRecoveryOrProviderAndListIsReadOnly(){
        allowReads();when(authorization.ownedResourceIds(actor,"conversation")).thenReturn(List.of(id.toString()));
        var s=service();var detail=s.detail(actor,id);
        assertTrue(detail.readOnly());assertFalse(detail.busy());assertEquals("running",detail.runs().get(0).status());
        var summary=s.list(actor).get(0);assertTrue(summary.readOnly());assertFalse(summary.busy());
        assertEquals(id,s.require(actor,id).id());assertEquals(id,s.get(actor,id).id());
        verify(repository,never()).save(any());verify(repository,never()).find(any());verify(repository,never()).list();verifyNoInteractions(agents);assertFalse(guard.isBusy(id));
    }
    @Test void changedArchiveCannotFallBackToUnverifiedBody(){
        allowReads();when(repository.findArchived(id,digest)).thenThrow(new ConversationException("CONVERSATION_ARCHIVE_UNAVAILABLE","Archive unavailable",HttpStatus.CONFLICT));
        assertEquals("CONVERSATION_ARCHIVE_UNAVAILABLE",assertThrows(ConversationException.class,()->service().detail(actor,id)).getCode());
        verify(repository,never()).find(any());verify(repository,never()).save(any());
    }
    @Test void foreignMetadataDecisionNeverTouchesFile(){
        when(authorization.legacyConversationDigest(actor,id)).thenThrow(new SecurityFailure("RESOURCE_UNAVAILABLE",403));
        assertThrows(SecurityFailure.class,()->service().detail(actor,id));verifyNoInteractions(repository);
    }
    @Test void continuationIsRejectedBeforeProviderAndRunOwnerRegistration(){
        allowReads();when(authorization.decide(eq(actor),eq("agent.execute"),isNull(),eq(Phase.ENTRY),isNull())).thenReturn(new AuthorizationDecision("ok","test",true,"ALLOWED"));
        when(authorization.decide(eq(actor),eq("conversation.writeOwn"),eq(new ResourceRef("conversation",id.toString())),eq(Phase.ENTRY),isNull())).thenReturn(new AuthorizationDecision("readonly","test",false,"ARCHIVE_READ_ONLY"));
        var mapper=new ConversationMessageMapper();var coordinator=new AgentConversationService(service(),repository,guard,new ConversationContextWindow(mapper,8),mapper,agents);coordinator.setAuthorizationService(authorization);
        assertEquals("ARCHIVE_READ_ONLY",assertThrows(SecurityFailure.class,()->coordinator.prepareChat(actor,"must not execute",id.toString(),AgentRunOptions.NONE)).code());
        assertEquals("ARCHIVE_READ_ONLY",assertThrows(SecurityFailure.class,()->coordinator.previewContext(actor,id.toString(),"must not load provider")).code());
        verifyNoInteractions(agents);verify(authorization,never()).registerResource(any(),any());verifyNoInteractions(repository);assertFalse(guard.isBusy(id));
    }
    @Test void ordinaryConversationRemainsWritableAndUnarchived(){
        doAnswer(c->((Supplier<?>)c.getArgument(5)).get()).when(authorization).gate(eq(actor),eq("conversation.readOwn"),any(),eq(Phase.HISTORY_READ),isNull(),any());
        var at=Instant.now();when(repository.find(id)).thenReturn(Optional.of(new Conversation(id,"ordinary",at,at,0,List.of(),List.of(),"safe")));
        var detail=service().detail(actor,id);assertFalse(detail.readOnly());assertFalse(detail.busy());verify(repository,never()).findArchived(any(),any());
    }
}
