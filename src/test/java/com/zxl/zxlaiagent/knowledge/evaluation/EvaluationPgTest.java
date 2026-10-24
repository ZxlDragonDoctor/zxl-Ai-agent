package com.zxl.zxlaiagent.knowledge.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zxl.zxlaiagent.knowledge.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit real-PG invocation: no skip, no in-memory replacement. Only fixed synthetic scopes are seeded. */
class EvaluationPgTest {
    @Test void seedSnapshotFourStrategiesAndExportMeasuredReport() throws Exception {
        var db=new KnowledgeDatabase(System.getenv("LESSON12_DB_URL"),System.getenv("LESSON12_DB_USER"),System.getenv("LESSON12_DB_PASSWORD"));
        var embedding=new DemoKnowledgeEmbedding();var service=new EvaluationService(db,embedding);
        var status=service.seed(false);assertTrue(status.snapshots().demoReady());assertFalse(status.realAvailable());
        // Full-set correctness is not a 60s latency benchmark. Use the supported explicit
        // batch budget; the separate 1s case below still verifies honest partial results.
        var report=service.run(new EvaluationService.Input("all",3,false,120000L));
        assertTrue(report.complete(),"A partial run must never count as a complete fixed-set evaluation");
        assertEquals(22,report.queryCount());assertEquals(8,report.strategies().size());assertEquals(22,report.embeddingCalls());assertEquals(44,report.embeddingCacheHits());
        assertEquals(88,report.strategies().stream().mapToInt(s->s.queries().size()).sum());
        assertEquals(0,report.strategies().stream().mapToInt(s->s.metrics().errorCount()).sum());
        assertEquals(0,report.strategies().stream().mapToInt(s->s.metrics().forbiddenCount()).sum());
        assertFalse(report.realSemantic());assertTrue(report.strategies().stream().filter(s->s.mode().equals("rerank")).flatMap(s->s.queries().stream()).allMatch(q->q.reranker().startsWith("deterministic")||q.reranker().contains("rule")));
        var mapper=new ObjectMapper();Path output=Path.of("target","lesson13-evaluation-report.json");mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(),report);
        mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of("target","lesson13-evaluation-fixture.json").toFile(),report.dataset());
        var csv=new StringBuilder("mode,split,recall,mrr,ndcg,noAnswerCount,noAnswerEmptyRate,forbiddenCount,p50Ms,p95Ms,errorCount\n");
        for(var strategy:report.strategies()){var m=strategy.metrics();csv.append(strategy.mode()).append(',').append(strategy.split()).append(',').append(m.recall()).append(',').append(m.mrr()).append(',').append(m.ndcg()).append(',').append(m.noAnswerCount()).append(',').append(m.noAnswerEmptyRate()).append(',').append(m.forbiddenCount()).append(',').append(m.p50Ms()).append(',').append(m.p95Ms()).append(',').append(m.errorCount()).append('\n');}
        Files.writeString(Path.of("target","lesson13-evaluation-report.csv"),csv);
        assertThrows(IllegalArgumentException.class,()->service.run(new EvaluationService.Input("all",3,true)));
        var knowledge=new KnowledgeService(db,EvaluationDataset.TENANT,embedding);
        try{knowledge.importDocument("eval-vpn809","vpn809.md","snapshot changed by the snapshot validation test");assertFalse(service.status().snapshots().demoReady());assertThrows(IllegalArgumentException.class,()->service.run(new EvaluationService.Input("holdout",3,false)));}
        finally{service.seed(false);}
        assertTrue(service.status().snapshotReady());
        var partial=service.run(new EvaluationService.Input("all",3,false,1000L));assertFalse(partial.complete());assertTrue(partial.strategies().stream().mapToInt(s->s.queries().size()).sum()<88);assertEquals(1000,partial.config().budgetMs());
    }
}
