package com.zxl.zxlaiagent.knowledge.evaluation;

import com.zxl.zxlaiagent.knowledge.KnowledgeEmbedding;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class EvaluationServiceTest {
    @Test void oneModelCallPerQuestionAndDefensiveCopies(){
        var calls=new AtomicInteger();var duration=new ArrayList<Duration>();
        KnowledgeEmbedding delegate=new KnowledgeEmbedding(){
            public float[] embed(String text){throw new AssertionError("Budget overload required");}
            public float[] embed(String text,Duration remaining){calls.incrementAndGet();duration.add(remaining);return new float[]{1,2};}
            public int dimensions(){return 2;}
            public String identity(){return "real-test-model";}
        };
        var cache=new EvaluationService.CachedEmbedding(delegate);float[] one=cache.embed("q",Duration.ofMillis(701));one[0]=99;
        assertArrayEquals(new float[]{1,2},cache.embed("q",Duration.ofMillis(600)));assertEquals(1,calls.get());assertEquals(1,cache.hits);
        assertEquals(Duration.ofMillis(701),duration.get(0));assertEquals("real-test-model",cache.identity());
        assertThrows(IllegalStateException.class,()->cache.embed("q",Duration.ZERO));
    }
    @Test void errorsCannotBecomeSuccessfulNoAnswerAndUndefinedGroupsStayUndefined(){
        var empty=EvaluationMetrics.score(Map.of(),Set.of(),List.of(),3);
        var rows=List.of(new EvaluationService.QueryResult("no","无答案","tuning",List.of(),empty,10,null,List.of(),"rules"),
                new EvaluationService.QueryResult("error","失败","tuning",List.of(),null,50,"FAILED",List.of(),"unavailable"));
        var aggregate=EvaluationService.aggregate(rows);assertNull(aggregate.recall());assertNull(aggregate.ndcg());assertEquals(1,aggregate.noAnswerCount());assertEquals(1.0,aggregate.noAnswerEmptyRate());assertEquals(1,aggregate.errorCount());assertEquals(1,aggregate.evaluatedCount());assertEquals(10,aggregate.p50Ms());assertEquals(50,aggregate.p95Ms());
    }
    @Test void dimensionsArePartOfFixedSnapshotAndModelSpace(){
        var first=EvaluationDataset.create("model-a",8);var second=EvaluationDataset.create("model-a",16);var third=EvaluationDataset.create("model-b",8);
        assertNotEquals(first.snapshotId(),second.snapshotId());assertNotEquals(first.snapshotId(),third.snapshotId());
        assertNotEquals(first.chunks().keySet(),second.chunks().keySet());
        assertEquals(9,first.questions().stream().filter(q->q.split().equals("tuning")).count());assertEquals(13,first.questions().stream().filter(q->q.split().equals("holdout")).count());
    }
    @Test void stagePercentilesAndSafetyAreRetainedWhenRetrievalDegrades(){
        var score=EvaluationMetrics.score(Map.of("a",2),Set.of("old"),List.of("old","a"),3);
        var stage=new com.zxl.zxlaiagent.knowledge.retrieval.RetrievalService.Stage("vector","FAILED",37,0);
        var row=new EvaluationService.QueryResult("failed","q","holdout",List.of("old","a"),score,80,"DEGRADED",List.of("VECTOR_CHANNEL_FAILED"),"rules",List.of(stage));
        var metrics=EvaluationService.aggregate(List.of(row));
        assertNull(metrics.recall());assertEquals(1,metrics.errorCount());assertEquals(1,metrics.forbiddenCount());
        assertEquals(37,metrics.stageLatency().get("vector").p95Ms());assertEquals(1,metrics.stageLatency().get("vector").samples());
    }
}
