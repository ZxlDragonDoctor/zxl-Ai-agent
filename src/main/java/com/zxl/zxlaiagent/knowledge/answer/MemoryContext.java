package com.zxl.zxlaiagent.knowledge.answer;

import java.time.Duration;
import java.util.List;

/** Trusted adapter owns identity mapping; recalled preferences never authorize tools or support enterprise facts. */
@FunctionalInterface
public interface MemoryContext {
 MemoryView read(String profile,String conversationId,Duration remaining);
 record Fact(String id,String key,String value,long version) { }
 record MemoryView(List<Fact> facts,String identity,int usedChars,int droppedCount) { }
 static MemoryContext empty(){return (p,c,t)->new MemoryView(List.of(),"disabled",0,0);}
}
