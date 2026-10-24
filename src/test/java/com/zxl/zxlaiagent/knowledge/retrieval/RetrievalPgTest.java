package com.zxl.zxlaiagent.knowledge.retrieval;

import com.zxl.zxlaiagent.knowledge.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit real-PG suite; missing configuration fails rather than skipping or falling back. */
class RetrievalPgTest {
    KnowledgeDatabase db; String tenant; KnowledgeEmbedding embedding;
    KnowledgeService ingestion;
    @BeforeEach void setup() {
        db=new KnowledgeDatabase(System.getenv("LESSON12_DB_URL"),System.getenv("LESSON12_DB_USER"),System.getenv("LESSON12_DB_PASSWORD"));
        tenant="retrieval_"+UUID.randomUUID().toString().replace("-","");
        embedding=new DemoKnowledgeEmbedding(); ingestion=new KnowledgeService(db,tenant,embedding);
        ingestion.importDocument("vpn809","vpn809.md","# VPN 809\n\nVPN 提示809时检查防火墙 UDP 500 和4500端口。");
        ingestion.importDocument("vpn691","vpn691.md","# VPN 691\n\nVPN 提示691时检查账号密码与认证权限。");
        ingestion.importDocument("password","password.md","# 密码重置\n\n忘记密码可以通过自助平台重置密码。");
    }
    RetrievalService.Request request(String query,String mode,boolean rerank,boolean rewrite) {
        return new RetrievalService.Request(query,mode,3,20,rerank,rewrite,10000);
    }
    @Test void modesReturnRealChannelsAndKeywordErrorCodeRanksFirst() {
        var service=new RetrievalService(db,tenant,embedding);
        var keyword=service.retrieve(request("809","keyword",false,false));
        assertEquals("vpn809",keyword.hits().get(0).documentId());
        assertEquals(List.of("keyword"),keyword.hits().get(0).channels());
        assertNull(keyword.hits().get(0).distance()); assertEquals(1,keyword.hits().get(0).keywordRank());
        var vector=service.retrieve(request("VPN 提示809","vector",false,false));
        assertEquals(3,vector.hits().size()); assertNotNull(vector.hits().get(0).distance());
        assertEquals(List.of("vector"),vector.hits().get(0).channels());
        var hybrid=service.retrieve(request("VPN 809","hybrid",true,false));
        assertEquals("rule-keyword-v1",hybrid.reranker()); assertEquals("vpn809",hybrid.hits().get(0).documentId());
        assertEquals(2,hybrid.hits().get(0).channels().size());
    }
    @Test void emptyKeywordIsSuccessfulShortageAndSqlSyntaxIsLiteral() {
        var service=new RetrievalService(db,tenant,embedding);
        var empty=service.retrieve(request("zznotfound987654","keyword",false,false));
        assertTrue(empty.hits().isEmpty()); assertEquals("NO_CURRENT_MATCHES",empty.shortageReason());
        assertTrue(empty.degradations().isEmpty());
        assertTrue(service.retrieve(request("% _ ' ;","keyword",false,false)).hits().isEmpty());
    }
    @Test void activeModelAndTenantFiltersApplyToEveryChannel() {
        new KnowledgeService(db,tenant+"other",embedding).importDocument("private","private.md","809 secret");
        ingestion.importDocument("vpn809","vpn809.md","VPN 809 新版规则");
        var service=new RetrievalService(db,tenant,embedding);
        for(String mode:List.of("vector","keyword","hybrid")) {
            var result=service.retrieve(request("809",mode,false,false));
            assertTrue(result.hits().stream().noneMatch(h->h.documentId().equals("private")||h.text().contains("UDP")));
        }
        KnowledgeEmbedding another=new KnowledgeEmbedding() {
            public float[] embed(String text) { return embedding.embed(text); }
            public String identity() { return "different-space-same-dimensions"; }
        };
        var mismatch=assertThrows(RetrievalService.Failure.class,()->new RetrievalService(db,tenant,another).retrieve(request("809","hybrid",false,false)));
        assertEquals("NO_COMPATIBLE_INDEX_PROFILE",mismatch.code());
        assertTrue(new RetrievalService(db,tenant+"empty",another).retrieve(request("809","hybrid",false,false)).hits().isEmpty());
        new KnowledgeService(db,tenant,another).importDocument("matching","matching.md","809 matching profile only");
        var matching=new RetrievalService(db,tenant,another).retrieve(request("809","hybrid",false,false));
        assertEquals(1,matching.hits().size());assertEquals("matching",matching.hits().get(0).documentId());
        ingestion.delete("vpn809");
        assertTrue(service.retrieve(request("809","keyword",false,false)).hits().isEmpty());
    }
    @Test void brokenVectorFallsBackOnlyInHybridAndAllFailureIsExplicit() {
        KnowledgeEmbedding broken=text->{throw new IllegalStateException("provider unavailable");};
        var service=new RetrievalService(db,tenant,broken);
        var result=service.retrieve(request("809","hybrid",false,false));
        assertEquals(List.of("VECTOR_CHANNEL_FAILED"),result.degradations());
        assertEquals("vpn809",result.hits().get(0).documentId());
        var error=assertThrows(RetrievalService.Failure.class,()->service.retrieve(request("809","vector",false,false)));
        assertEquals("ALL_RETRIEVAL_CHANNELS_FAILED",error.code());
    }
    @Test void invalidRerankerFallsBackAndFinalCheckRemovesConcurrentDeletion() {
        Reranker invalid=(q,h,t)->List.of(new Reranker.Score("injected",Double.NaN));
        var fallback=new RetrievalService(db,tenant,embedding,invalid).retrieve(request("809","hybrid",true,false));
        assertEquals("fallback-rrf",fallback.reranker());
        assertTrue(fallback.degradations().contains("RERANK_FAILED_OR_BUDGET_EXHAUSTED"));
        Reranker deletes=(q,h,t)->{
            ingestion.delete("vpn809");
            return h.stream().map(candidate->new Reranker.Score(candidate.chunkId(),1)).toList();
        };
        var result=new RetrievalService(db,tenant,embedding,deletes).retrieve(request("809","keyword",true,false));
        assertTrue(result.hits().isEmpty()); assertEquals("NO_CURRENT_MATCHES",result.shortageReason());
    }
    @Test void rewriteIsOnceLexicalAndCancellationStopsBeforeIo() {
        var service=new RetrievalService(db,tenant,embedding);
        var result=service.retrieve(request("密码忘了","keyword",false,true));
        assertEquals("密码忘了",result.query()); assertTrue(result.rewrittenQuery().contains("重置"));
        assertEquals("password",result.hits().get(0).documentId());
        Thread.currentThread().interrupt();
        try { assertThrows(RetrievalDeadline.Cancelled.class,()->service.retrieve(request("809","hybrid",false,false))); }
        finally { Thread.interrupted(); }
    }
    @Test void remainingBudgetReachesEmbeddingAndDatabaseHonorsStatementTimeout() {
        KnowledgeEmbedding observing=new KnowledgeEmbedding() {
            public float[] embed(String text) { throw new AssertionError("Budget overload must be used"); }
            public float[] embed(String text,java.time.Duration remaining) { assertTrue(remaining.toMillis()<=10000);return embedding.embed(text); }
        };
        assertFalse(new RetrievalService(db,tenant,observing).retrieve(request("809","vector",false,false)).hits().isEmpty());
        long start=System.nanoTime();
        assertThrows(IllegalStateException.class,()->db.tx(java.time.Duration.ofMillis(150),connection->{
            try(var statement=connection.createStatement()) { statement.execute("SELECT pg_sleep(2)"); } return null;
        }));
        assertTrue((System.nanoTime()-start)/1_000_000<1500,"SQL cancellation should stop the 2-second sleep");
    }
    @Test void slowRerankerIsCancelledAndReservedBudgetAllowsValidatedRrfFallback() throws Exception {
        java.util.concurrent.CountDownLatch interrupted=new java.util.concurrent.CountDownLatch(1);
        Reranker slow=(q,h,remaining)->{
            try { Thread.sleep(5000); }
            catch(InterruptedException failure) { interrupted.countDown(); Thread.currentThread().interrupt(); }
            return h.stream().map(candidate->new Reranker.Score(candidate.chunkId(),1)).toList();
        };
        var response=new RetrievalService(db,tenant,embedding,slow).retrieve(new RetrievalService.Request("809","keyword",1,20,true,false,1500));
        assertEquals("fallback-rrf",response.reranker()); assertEquals("vpn809",response.hits().get(0).documentId());
        assertTrue(response.elapsedMs()<1500); assertTrue(interrupted.await(200,java.util.concurrent.TimeUnit.MILLISECONDS));
    }
    @Test void cancellingCallerInterruptsItsInFlightSql() throws Exception {
        var entered=new java.util.concurrent.CountDownLatch(1);
        var finished=new java.util.concurrent.CountDownLatch(1);
        var caller=new Thread(()->{
            try { db.tx(java.time.Duration.ofSeconds(5),connection->{
                try(var statement=connection.createStatement()) { entered.countDown(); statement.execute("SELECT pg_sleep(4)"); }
                return null;
            }); } catch(IllegalStateException expected) { }
            finally { finished.countDown(); }
        });
        caller.start(); assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS)); caller.interrupt();
        assertTrue(finished.await(700,java.util.concurrent.TimeUnit.MILLISECONDS),"Cancellation must stop SQL before its four-second wait");
        caller.join(5000);
    }
}
