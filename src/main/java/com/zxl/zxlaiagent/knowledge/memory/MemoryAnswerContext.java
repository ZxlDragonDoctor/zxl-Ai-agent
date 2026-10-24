package com.zxl.zxlaiagent.knowledge.memory;
import com.zxl.zxlaiagent.knowledge.KnowledgeDatabase;
import com.zxl.zxlaiagent.knowledge.answer.MemoryContext;
import java.time.*;
/** No session history is persisted here; facts are scoped to a registered demo identity. */
public final class MemoryAnswerContext implements MemoryContext {
 private final KnowledgeDatabase database;private final Clock clock;
 public MemoryAnswerContext(KnowledgeDatabase database,Clock clock){this.database=database;this.clock=clock;}
 @Override public MemoryView read(String profile,String conversationId,Duration remaining){var scope=MemoryService.demoScope(profile);var recalled=new MemoryService(database,scope,clock).recall(1000,remaining);return new MemoryView(recalled.facts().stream().map(f->new Fact(f.id(),f.factKey(),f.value(),f.version())).toList(),scope.tenantId()+"/"+scope.userId(),recalled.usedChars(),recalled.droppedCount());}
}
