package com.zxl.zxlaiagent.conversation.service;

import com.zxl.zxlaiagent.agent.ToolCallAgent;
import com.zxl.zxlaiagent.agent.model.*;
import com.zxl.zxlaiagent.conversation.agent.*;
import com.zxl.zxlaiagent.conversation.model.*;
import com.zxl.zxlaiagent.conversation.repository.ConversationRepository;
import com.zxl.zxlaiagent.security.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.tool.ToolCallback;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ProviderExecutionPermissionsTest {
    private final ActorRef actor=new ActorRef("subject","tenant","session",1);
    private final AuthorizationService authorization=mock(AuthorizationService.class);
    private final ConversationRepository repository=mock(ConversationRepository.class);
    private final ConversationRunGuard guard=new ConversationRunGuard();
    private final UUID conversationId=UUID.randomUUID();
    private final DeferredAgent agent=new DeferredAgent();
    private final AtomicReference<Conversation> stored=new AtomicReference<>(new Conversation(conversationId,"test",Instant.now(),Instant.now(),0,List.of(),List.of(),"extension"));
    private final ConversationAgentProvider provider=new ConversationAgentProvider(){
        public AgentDefinition definition(){return new AgentDefinition("extension","扩展","Extension","","");}
        public ToolCallAgent create(){throw new AssertionError("No legacy fallback");}
        public ToolCallAgent create(ActorRef actor,AgentRunOptions options){return agent;}
        public List<String> executionPermissions(){return List.of("skill.execute","knowledge.read");}
    };
    private AgentConversationService coordinator(){
        when(authorization.decide(any(),anyString(),nullable(ResourceRef.class),any(),nullable(String.class)))
                .thenReturn(new AuthorizationDecision("decision","test:1",true,"ALLOWED"));
        when(authorization.gate(any(),anyString(),any(),any(),nullable(String.class),any()))
                .thenAnswer(call->((Supplier<?>)call.getArgument(5)).get());
        when(authorization.gateAll(any(),anyList(),any(),nullable(String.class),any()))
                .thenAnswer(call->((Supplier<?>)call.getArgument(4)).get());
        when(repository.find(conversationId)).thenAnswer(call->Optional.of(stored.get()));
        doAnswer(call->{stored.set(call.getArgument(0));return null;}).when(repository).save(any());
        var registry=new ConversationAgentRegistry(List.of(provider));
        var conversations=new ConversationService(repository,guard,registry);conversations.setAuthorizationService(authorization);
        var mapper=new ConversationMessageMapper();
        var service=new AgentConversationService(conversations,repository,guard,new ConversationContextWindow(mapper,8),mapper,registry);
        service.setAuthorizationService(authorization);return service;
    }
    @Test void registeredProviderPermissionsReachActualPublicationBoundaryWithoutTypeSwitch(){
        coordinator().prepareChat(actor,"question",conversationId.toString(),AgentRunOptions.NONE);
        agent.checkPublication();
        verify(authorization).gateAll(eq(actor),argThat(required->required.contains(new AuthorizationService.Permission("skill.execute",null))
                &&required.contains(new AuthorizationService.Permission("knowledge.read",null))
                &&required.contains(new AuthorizationService.Permission("agent.execute",null))
                &&required.stream().anyMatch(p->p.action().equals("run.readOwn"))),eq(Phase.RESULT_PUBLISH),anyString(),any());
    }
    @Test void finalPersistenceRechecksProviderActionsAndDenialDiscardsSensitiveMessagesAndEvents(){
        coordinator().prepareChat(actor,"question",conversationId.toString(),AgentRunOptions.NONE);
        when(authorization.gateAll(eq(actor),argThat(required->required.contains(new AuthorizationService.Permission("knowledge.read",null))),eq(Phase.RESULT_PERSIST),anyString(),any()))
                .thenThrow(new SecurityFailure("ACTION_DENIED",403));
        assertThrows(SecurityFailure.class,agent::completeSensitive);
        assertFalse(guard.isBusy(conversationId));
        assertFalse(stored.get().messages().toString().contains("sensitive-answer"));
        assertTrue(stored.get().runs().get(0).answer().isEmpty());
        assertTrue(stored.get().runs().get(0).events().isEmpty());
        assertEquals("error",stored.get().runs().get(0).status());
    }
    @Test void successfulPersistenceIncludesOwnershipWriteAndAllExecutionActions(){
        coordinator().prepareChat(actor,"question",conversationId.toString(),AgentRunOptions.NONE);
        agent.completeSensitive();
        verify(authorization).gateAll(eq(actor),argThat(required->required.contains(new AuthorizationService.Permission("skill.execute",null))
                &&required.contains(new AuthorizationService.Permission("knowledge.read",null))
                &&required.contains(new AuthorizationService.Permission("conversation.writeOwn",new ResourceRef("conversation",conversationId.toString())))),
                eq(Phase.RESULT_PERSIST),anyString(),any());
        assertEquals("completed",stored.get().runs().get(0).status());
        assertTrue(stored.get().messages().toString().contains("sensitive-answer"));
    }
    static final class DeferredAgent extends ToolCallAgent{
        DeferredAgent(){super(new ToolCallback[0]);setSystemPrompt("Safe role");}
        void checkPublication(){operationBoundary().check(Phase.RESULT_PUBLISH);}
        void completeSensitive(){
            getMessageList().add(new UserMessage("question"));getMessageList().add(new AssistantMessage("sensitive-answer"));
            getCompletionListener().accept(new AgentRunSnapshot(getMessageList(),AgentEndReason.ANSWER_READY,null));
        }
    }
}
