package com.zxl.zxlaiagent.knowledge.retrieval;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RetrievalAlgorithmsTest {
    @Test void rrfUsesOneBasedRanksAndDeduplicatesEachChannel() {
        var a = hit("a"); var b = hit("b");
        var fused = RankFusion.fuse(List.of(a, a, b), List.of(b, a), 20);
        assertEquals("a", fused.get(0).chunkId());
        assertEquals(1d / 61 + 1d / 62, fused.get(0).fusionScore(), 1e-12);
        assertEquals(2, fused.get(1).vectorRank());
        assertEquals(2, fused.size());
    }
    @Test void chineseTermsPreserveErrorCodesAndDictionaryWords() {
        var terms = QueryTerms.extract("VPN提示809，账号被锁定");
        assertTrue(terms.contains("809")); assertTrue(terms.contains("vpn"));
        assertTrue(terms.contains("账号")); assertTrue(terms.contains("锁定"));
    }
    @Test void rerankerCannotInventDuplicateOmitOrReturnNonFiniteScores() {
        var hits = List.of(hit("a"), hit("b"));
        assertThrows(IllegalArgumentException.class, () -> Reranker.validate(hits, List.of(new Reranker.Score("x", 1))));
        assertThrows(IllegalArgumentException.class, () -> Reranker.validate(hits, List.of(new Reranker.Score("a", 1), new Reranker.Score("a", 2))));
        assertThrows(IllegalArgumentException.class, () -> Reranker.validate(hits, List.of(new Reranker.Score("a", Double.NaN), new Reranker.Score("b", 1))));
    }
    @Test void budgetChecksCancellationAndExpiry() throws Exception {
        var deadline = new RetrievalDeadline(1); Thread.sleep(5);
        assertThrows(RetrievalDeadline.Expired.class, deadline::remaining);
        Thread.currentThread().interrupt();
        try { assertThrows(RetrievalDeadline.Cancelled.class, () -> new RetrievalDeadline(100).remaining()); }
        finally { Thread.interrupted(); }
    }
    @Test void endpointRejectsUnknownScopeWrongTypesAndOutOfRangeOptions() throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        assertThrows(IllegalArgumentException.class,()->RetrievalController.parse(json.readTree("{\"query\":\"809\",\"tenant\":\"other\"}")));
        assertThrows(IllegalArgumentException.class,()->RetrievalController.parse(json.readTree("{\"query\":\"809\",\"rerank\":\"true\"}")));
        assertThrows(IllegalArgumentException.class,()->RetrievalController.parse(json.readTree("{\"query\":\"809\",\"topK\":6}")));
        assertEquals("hybrid",RetrievalController.parse(json.readTree("{\"query\":\"809\"}")).mode());
    }
    static RetrievalService.Hit hit(String id) {
        return new RetrievalService.Hit(id,"doc","rev",1,"VPN 809",1,1,0,7,null,null,null,0,null,List.of());
    }
}
