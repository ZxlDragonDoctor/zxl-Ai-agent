package com.zxl.zxlaiagent.knowledge.scoped;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class WorkerRequestContractTest {
 @Test void canonicalQueryIsSortedAndBoundToBytes(){assertEquals("/ai/learning/knowledge/search?limit=5&q=a%20b",WorkerRequestContract.canonicalTarget("/ai/learning/knowledge/search","q=a+b&limit=5"));assertThrows(IllegalArgumentException.class,()->WorkerRequestContract.canonicalTarget("/x","q=a&q=b"));assertThrows(IllegalArgumentException.class,()->WorkerRequestContract.canonicalTarget("/x","profile=alice"));}
 @Test void routesHaveFixedActions(){assertEquals("knowledge.manage",WorkerRequestContract.action("POST","/ai/learning/knowledge/documents"));assertEquals("knowledge.read",WorkerRequestContract.action("POST","/ai/learning/knowledge/memories/proposals"));assertThrows(IllegalArgumentException.class,()->WorkerRequestContract.action("POST","/ai/learning/knowledge/evaluation/run"));}
}
