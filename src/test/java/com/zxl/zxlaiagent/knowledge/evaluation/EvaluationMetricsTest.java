package com.zxl.zxlaiagent.knowledge.evaluation;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EvaluationMetricsTest {
    @Test void handCalculatedRankingAndDeduplication() {
        var score=EvaluationMetrics.score(Map.of("a",2,"b",1,"noise",0),Set.of("old"),List.of("noise","a","a","b"),3);
        assertEquals(1.0,score.recall()); assertEquals(.5,score.mrr());
        assertEquals((3/Math.log(3)*Math.log(2)+1/2.0)/(3+1/Math.log(3)*Math.log(2)),score.ndcg(),1e-9);
    }
    @Test void noAnswerHasUndefinedRecallAndNdcgAndForbiddenIsCounted() {
        var score=EvaluationMetrics.score(Map.of("noise",0),Set.of("old"),List.of("old"),3);
        assertNull(score.recall());assertNull(score.ndcg());assertEquals(1,score.forbiddenCount());assertTrue(score.noAnswer());
    }
    @Test void fixedDatasetContainsTwentyQuestionsAndRejectsBrokenLabels() {
        var dataset=EvaluationDataset.create("deterministic-demo-v1",8);
        assertTrue(dataset.questions().size()>=20);
        dataset.validate(dataset.snapshotId(),dataset.modelIdentity(),dataset.chunks().keySet());
        assertThrows(IllegalArgumentException.class,()->dataset.validate("wrong",dataset.modelIdentity(),dataset.chunks().keySet()));
        assertThrows(IllegalArgumentException.class,()->dataset.validate(dataset.snapshotId(),"wrong",dataset.chunks().keySet()));
        assertThrows(IllegalArgumentException.class,()->dataset.validate(dataset.snapshotId(),dataset.modelIdentity(),Set.of()));
        var repeated=new ArrayList<>(dataset.questions());repeated.add(repeated.get(0));
        var broken=new EvaluationDataset(dataset.version(),dataset.snapshotId(),dataset.modelIdentity(),dataset.dimensions(),dataset.documents(),dataset.chunks(),repeated);
        assertThrows(IllegalArgumentException.class,()->broken.validate(dataset.snapshotId(),dataset.modelIdentity(),dataset.chunks().keySet()));
        assertTrue(dataset.questions().stream().anyMatch(q->q.labels().containsValue(0)));
    }
}
