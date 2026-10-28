package com.zxl.zxlaiagent.knowledge.scoped;
import com.zxl.zxlaiagent.knowledge.*;
import com.zxl.zxlaiagent.knowledge.retrieval.*;
import com.zxl.zxlaiagent.knowledge.memory.*;
import com.zxl.zxlaiagent.knowledge.answer.*;
import com.zxl.zxlaiagent.security.*;
import com.zxl.zxlaiagent.security.execution.*;
import com.zxl.zxlaiagent.conversation.repository.ConversationRepository;
import java.time.Clock;
import java.util.*;
/** Request-local scopes: tenant/subject come solely from the immutable authenticated reference. */
public final class ScopedKnowledgeFactory {
 private final KnowledgeDatabase db;private final AuthorizationService authorization;private final KnowledgeEmbedding embedding;private final Reranker reranker;private final AnswerModel model;private final Clock clock;private final ConversationRepository conversations;
 public ScopedKnowledgeFactory(KnowledgeDatabase db,AuthorizationService authorization,KnowledgeEmbedding embedding,Reranker reranker,AnswerModel model,Clock clock){this(db,authorization,embedding,reranker,model,clock,null);}
 public ScopedKnowledgeFactory(KnowledgeDatabase db,AuthorizationService authorization,KnowledgeEmbedding embedding,Reranker reranker,AnswerModel model,Clock clock,ConversationRepository conversations){this.db=Objects.requireNonNull(db);this.authorization=Objects.requireNonNull(authorization);this.embedding=Objects.requireNonNull(embedding);this.reranker=Objects.requireNonNull(reranker);this.model=model;this.clock=Objects.requireNonNull(clock);this.conversations=conversations;}
 private OperationBoundary boundary(ActorRef actor,String action){Objects.requireNonNull(actor);return new ActorBoundary(authorization,actor,List.of(new AuthorizationService.Permission(action,null)),null);}
 public KnowledgeService knowledge(ActorRef actor){return new KnowledgeService(db,actor.tenantId(),embedding,boundary(actor,"knowledge.read"),boundary(actor,"knowledge.manage"));}
 public RetrievalService retrieval(ActorRef actor){return new RetrievalService(db,actor.tenantId(),embedding,reranker,boundary(actor,"knowledge.read"));}
 public MemoryService memory(ActorRef actor){return new MemoryService(db,new MemoryService.Scope(actor.tenantId(),actor.subjectId()),clock,boundary(actor,"knowledge.read"),(session,message,text)->{
  var sourceBoundary=ownedBoundary(actor,session);sourceBoundary.check(Phase.SOURCE_READ);
  if(conversations==null)throw new SecurityFailure("SOURCE_UNAVAILABLE",404);
  var conversation=sourceBoundary.start(Phase.SOURCE_READ,()->conversations.find(UUID.fromString(session)).orElseThrow(()->new SecurityFailure("SOURCE_UNAVAILABLE",404)));
  if(conversation.messages().stream().noneMatch(m->m.messageId().toString().equals(message)&&m.role().equals("user")&&m.content().equals(text)))throw new SecurityFailure("SOURCE_UNAVAILABLE",404);
  return sourceBoundary;
 });}
 private OperationBoundary ownedBoundary(ActorRef actor,String id){return new ActorBoundary(authorization,actor,List.of(new AuthorizationService.Permission("knowledge.read",null),new AuthorizationService.Permission("conversation.readOwn",new ResourceRef("conversation",id))),null);}
 public AnswerService answer(ActorRef actor){var memory=memory(actor);MemoryContext context=(ignored,id,remaining)->{authorization.requireOwned(actor,new ResourceRef("conversation",id),Phase.SOURCE_READ);var recall=memory.recall(1000,remaining);return new MemoryContext.MemoryView(recall.facts().stream().map(f->new MemoryContext.Fact(f.id(),f.factKey(),f.value(),f.version())).toList(),"authenticated-preferences",recall.usedChars(),recall.droppedCount());};return new AnswerService(db,actor.tenantId(),retrieval(actor),model,context,boundary(actor,"knowledge.read"),id->authorization.requireOwned(actor,new ResourceRef("conversation",id),Phase.ENTRY),true,id->ownedBoundary(actor,id));}
}
