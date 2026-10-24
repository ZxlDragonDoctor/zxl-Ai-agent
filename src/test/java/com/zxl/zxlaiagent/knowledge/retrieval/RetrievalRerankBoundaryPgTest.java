package com.zxl.zxlaiagent.knowledge.retrieval;

import com.zxl.zxlaiagent.knowledge.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

/** Real PG reproducer: authorization must be fresh when a queued external rerank actually starts. */
class RetrievalRerankBoundaryPgTest {
    @Test void deletionWhileRerankIsQueuedMustPreventExternalTextDispatch() throws Exception {
        var db=new KnowledgeDatabase(System.getenv("LESSON12_DB_URL"),System.getenv("LESSON12_DB_USER"),System.getenv("LESSON12_DB_PASSWORD"));
        String tenant="boundary_"+UUID.randomUUID().toString().replace("-","");
        var embedding=new DemoKnowledgeEmbedding();var ingestion=new KnowledgeService(db,tenant,embedding);
        ingestion.importDocument("vpn809","vpn809.md","VPN 809 private-old-text must never cross rerank after deletion");
        var field=RetrievalService.class.getDeclaredField("RERANK_WORKERS");field.setAccessible(true);
        var workers=(ThreadPoolExecutor)field.get(null);var occupied=new CountDownLatch(2);var release=new CountDownLatch(1);
        Runnable blocker=()->{occupied.countDown();try{release.await();}catch(InterruptedException failure){Thread.currentThread().interrupt();}};
        Future<?> blockerOne=workers.submit(blocker),blockerTwo=workers.submit(blocker);
        var sentOldText=new AtomicBoolean();
        Reranker external=(q,candidates,remaining)->{if(candidates.stream().anyMatch(h->h.text().contains("private-old-text")))sentOldText.set(true);return candidates.stream().map(h->new Reranker.Score(h.chunkId(),1)).toList();};
        var caller=Executors.newSingleThreadExecutor();Future<RetrievalService.Response> response=null;
        try{
            assertTrue(occupied.await(2,TimeUnit.SECONDS));
            response=caller.submit(()->new RetrievalService(db,tenant,embedding,external).retrieve(new RetrievalService.Request("809","keyword",1,20,true,false,10000)));
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(workers.getQueue().isEmpty()&&System.nanoTime()<until)Thread.sleep(10);
            assertFalse(workers.getQueue().isEmpty(),"Target must finish authorization and enter rerank queue first");
            ingestion.delete("vpn809");release.countDown();
            assertTrue(response.get(5,TimeUnit.SECONDS).hits().isEmpty());
            assertFalse(sentOldText.get(),"Final filtering cannot undo sending a deleted candidate to an external reranker");
        }finally{
            release.countDown();blockerOne.cancel(true);blockerTwo.cancel(true);if(response!=null)response.cancel(true);caller.shutdownNow();
            ingestion.delete("vpn809");
        }
    }
}
