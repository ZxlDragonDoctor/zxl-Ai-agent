package com.zxl.zxlaiagent.knowledge.retrieval;

import com.zxl.zxlaiagent.knowledge.*;
import com.zxl.zxlaiagent.security.*;
import com.zxl.zxlaiagent.security.execution.OperationBoundary;
import org.junit.jupiter.api.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

class RetrievalVectorBudgetPgTest {
    KnowledgeDatabase db;KnowledgeService ingestion;String tenant;
    final KnowledgeEmbedding original=new DemoKnowledgeEmbedding();
    @BeforeEach void setup(){
        db=new KnowledgeDatabase(System.getenv("LESSON12_DB_URL"),System.getenv("LESSON12_DB_USER"),System.getenv("LESSON12_DB_PASSWORD"));
        tenant="vector_budget_"+java.util.UUID.randomUUID().toString().replace("-","");
        ingestion=new KnowledgeService(db,tenant,original);ingestion.importDocument("vpn809","vpn809.md","VPN 809 synthetic firewall evidence");
    }
    @AfterEach void cleanup(){ingestion.delete("vpn809");}
    RetrievalService.Request request(String mode){return new RetrievalService.Request("809",mode,1,5,false,false,1500);}
    KnowledgeEmbedding slow(CountDownLatch entered,CountDownLatch interrupted){return new KnowledgeEmbedding(){
        public float[] embed(String text){
            entered.countDown();try{Thread.sleep(5000);}catch(InterruptedException stopped){interrupted.countDown();Thread.currentThread().interrupt();}
            return original.embed(text);
        }
    };}
    @Test void slowHybridVectorCannotStarveKeywordAndFinalPgVisibility() throws Exception {
        var entered=new CountDownLatch(1);var interrupted=new CountDownLatch(1);
        var result=new RetrievalService(db,tenant,slow(entered,interrupted)).retrieve(request("hybrid"));
        assertEquals("hybrid",result.mode());assertEquals("vpn809",result.hits().get(0).documentId());
        assertEquals(java.util.List.of("keyword"),result.hits().get(0).channels());assertTrue(result.degradations().contains("VECTOR_CHANNEL_FAILED"));
        assertTrue(result.stages().stream().anyMatch(stage->stage.name().equals("final-visibility")&&stage.status().equals("OK")));
        assertTrue(result.elapsedMs()<1500);assertTrue(entered.await(1,TimeUnit.SECONDS));assertTrue(interrupted.await(1,TimeUnit.SECONDS));
    }
    @Test void vectorOnlyTimeoutCannotSilentlyUseKeyword() throws Exception {
        var entered=new CountDownLatch(1);var interrupted=new CountDownLatch(1);long start=System.nanoTime();
        var error=assertThrows(RetrievalService.Failure.class,()->new RetrievalService(db,tenant,slow(entered,interrupted)).retrieve(request("vector")));
        assertEquals("ALL_RETRIEVAL_CHANNELS_FAILED",error.code());assertTrue((System.nanoTime()-start)/1_000_000<1500);assertTrue(interrupted.await(1,TimeUnit.SECONDS));
    }
    @Test void revokedVectorFailureCannotBeReclassifiedAsAChannelDegradation(){
        var allowed=new AtomicBoolean(true);
        KnowledgeEmbedding revoked=text->{allowed.set(false);return original.embed(text);};
        OperationBoundary boundary=new OperationBoundary(){public void check(Phase phase){if(!allowed.get())throw new SecurityFailure("ACTION_DENIED",403);}public <T>T start(Phase phase,Supplier<T> action){check(phase);return action.get();}};
        assertThrows(SecurityFailure.class,()->new RetrievalService(db,tenant,revoked,Reranker.rules(),boundary).retrieve(request("hybrid")));
    }
    @Test void expiredGlobalBudgetDuringKeywordSqlIsFatalRatherThanAChannelDegradation(){
        var reads=new AtomicInteger();
        OperationBoundary boundary=new OperationBoundary(){
            public void check(Phase phase){
                if(phase==Phase.EVIDENCE_READ&&reads.incrementAndGet()==2){
                    try{Thread.sleep(1700);}catch(InterruptedException stopped){Thread.currentThread().interrupt();}
                }
            }
            public <T>T start(Phase phase,Supplier<T> action){check(phase);return action.get();}
        };
        KnowledgeEmbedding unavailable=text->{throw new IllegalStateException("synthetic fast provider failure");};
        assertThrows(RetrievalDeadline.Expired.class,()->new RetrievalService(db,tenant,unavailable,Reranker.rules(),boundary).retrieve(request("hybrid")));
    }
    @Test void callerCancellationInterruptsActualVectorWorker() throws Exception {
        var entered=new CountDownLatch(1);var interrupted=new CountDownLatch(1);var done=new CountDownLatch(1);var failure=new AtomicReference<RuntimeException>();
        var thread=new Thread(()->{try{new RetrievalService(db,tenant,slow(entered,interrupted)).retrieve(request("hybrid"));}catch(RuntimeException stopped){failure.set(stopped);}finally{done.countDown();}},"synthetic-retrieval-caller");
        thread.start();try{assertTrue(entered.await(2,TimeUnit.SECONDS));thread.interrupt();assertTrue(done.await(2,TimeUnit.SECONDS));assertInstanceOf(RetrievalDeadline.Cancelled.class,failure.get());assertTrue(interrupted.await(1,TimeUnit.SECONDS));}
        finally{thread.interrupt();thread.join(2000);}
    }
    @Test void queueWaitConsumesTheVectorChildBudgetWithoutStartingLateEmbedding() throws Exception {
        var field=RetrievalService.class.getDeclaredField("RERANK_WORKERS");field.setAccessible(true);var pool=(ThreadPoolExecutor)field.get(null);
        var occupied=new CountDownLatch(2);var release=new CountDownLatch(1);var calls=new AtomicInteger();
        Runnable block=()->{occupied.countDown();try{release.await();}catch(InterruptedException stopped){Thread.currentThread().interrupt();}};
        var first=pool.submit(block);var second=pool.submit(block);
        try{assertTrue(occupied.await(2,TimeUnit.SECONDS));KnowledgeEmbedding observing=text->{calls.incrementAndGet();return original.embed(text);};
            var response=new RetrievalService(db,tenant,observing).retrieve(request("hybrid"));
            assertEquals("vpn809",response.hits().get(0).documentId());assertEquals(0,calls.get());assertTrue(response.degradations().contains("VECTOR_CHANNEL_FAILED"));
        }finally{release.countDown();first.cancel(true);second.cancel(true);pool.purge();}
    }
}
