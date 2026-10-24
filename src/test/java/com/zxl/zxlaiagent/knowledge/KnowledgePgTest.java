package com.zxl.zxlaiagent.knowledge;

import org.junit.jupiter.api.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in invocation requires a real PostgreSQL; no H2 fallback and no skipped tests. */
class KnowledgePgTest {
    private KnowledgeDatabase db; private String tenant;
    @BeforeEach void setup(){db=new KnowledgeDatabase(System.getenv("LESSON12_DB_URL"),System.getenv("LESSON12_DB_USER"),System.getenv("LESSON12_DB_PASSWORD"));tenant="test_"+java.util.UUID.randomUUID().toString().replace("-","");}
    private KnowledgeService service(KnowledgeEmbedding e){return new KnowledgeService(db,tenant,e);}
    @Test void lifecyclePersistsRawAndVectorsDeduplicatesUpdatesDeletes(){
        AtomicInteger calls=new AtomicInteger(); KnowledgeEmbedding e=text->{calls.incrementAndGet();return new DemoKnowledgeEmbedding().embed(text);};var s=service(e);
        String first="# VPN\n\n检查网络😀。";var imported=s.importDocument("vpn","vpn.md",first);assertEquals("IMPORTED",imported.outcome());int n=calls.get();
        assertEquals("UNCHANGED",s.importDocument("vpn","vpn.md",first).outcome());assertEquals(n,calls.get());
        assertEquals(first,s.detail("vpn").rawText());assertEquals(1,s.search(first,5).hits().size());assertEquals(0,s.search(first,5).hits().get(0).distance(),0.00001);
        var reopened=new KnowledgeService(new KnowledgeDatabase(System.getenv("LESSON12_DB_URL"),System.getenv("LESSON12_DB_USER"),System.getenv("LESSON12_DB_PASSWORD")),tenant,e);
        assertEquals(first,reopened.detail("vpn").rawText());
        var updated=s.importDocument("vpn","vpn.md","# VPN\n\n请联系管理员。");assertNotEquals(imported.document().activeRevision(),updated.document().activeRevision());
        assertTrue(s.search(first,5).hits().stream().noneMatch(h->h.text().contains("检查网络")));
        s.delete("vpn");assertTrue(s.search(first,5).hits().isEmpty());assertEquals("DELETED",s.detail("vpn").document().status());
    }
    @Test void embeddingFailureKeepsPreviousActiveAndIsReported(){
        var normal=service(new DemoKnowledgeEmbedding());normal.importDocument("vpn","vpn.md","old");
        var failing=service(t->{throw new IllegalStateException("injected embedding failure");});
        assertThrows(IllegalStateException.class,()->failing.importDocument("vpn","vpn.md","new"));
        assertEquals("old",normal.detail("vpn").rawText());assertEquals("EMBEDDING_FAILED",normal.detail("vpn").document().errorCode());
    }
    @Test void concurrentDeleteCannotBeResurrectedByOlderBuild() throws Exception {
        var s=service(new DemoKnowledgeEmbedding());s.importDocument("vpn","vpn.md","old");CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
        var slow=service(t->{started.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("wait");}catch(InterruptedException e){throw new RuntimeException(e);}return new DemoKnowledgeEmbedding().embed(t);});
        var pool=Executors.newSingleThreadExecutor();try{var future=pool.submit(()->slow.importDocument("vpn","vpn.md","new"));assertTrue(started.await(10,TimeUnit.SECONDS));s.delete("vpn");release.countDown();assertThrows(ExecutionException.class,()->future.get(10,TimeUnit.SECONDS));assertEquals("DELETED",s.detail("vpn").document().status());assertTrue(s.search("new",5).hits().isEmpty());}finally{release.countDown();pool.shutdownNow();}
    }
    @Test void tenantSeparationAndDimensionValidation(){var s=service(new DemoKnowledgeEmbedding());s.importDocument("vpn","vpn.md","private");var other=new KnowledgeService(db,tenant+"b",new DemoKnowledgeEmbedding());assertTrue(other.list().isEmpty());assertTrue(other.search("private",5).hits().isEmpty());assertThrows(IllegalArgumentException.class,()->service(t->new float[]{1}).importDocument("bad","bad.md","bad"));assertThrows(IllegalArgumentException.class,()->s.search("x",51));}
    @Test void publicationSqlFailureRollsBackChunksAndActivePointer(){
        var s=service(new DemoKnowledgeEmbedding());var old=s.importDocument("vpn","vpn.md","stable");
        String constraint="test_"+tenant.substring(5);
        // The database is persistent across runs. Scope the fault to this test's
        // generated tenant, so earlier successful retry rows do not invalidate ADD CHECK.
        db.tx(c->{try(var st=c.createStatement()){st.execute("ALTER TABLE knowledge_chunk ADD CONSTRAINT "+constraint+" CHECK (tenant_id <> '"+tenant+"' OR text_content <> 'reject_this')");}return null;});
        try {assertThrows(IllegalStateException.class,()->s.importDocument("vpn","vpn.md","reject_this"));assertEquals(old.document().activeRevision(),s.detail("vpn").document().activeRevision());assertEquals("stable",s.detail("vpn").rawText());assertEquals("PUBLISH_FAILED",s.detail("vpn").document().errorCode());}
        finally{db.tx(c->{try(var st=c.createStatement()){st.execute("ALTER TABLE knowledge_chunk DROP CONSTRAINT "+constraint);}return null;});}
        assertEquals("IMPORTED",s.importDocument("vpn","vpn.md","reject_this").outcome());
    }
    @Test void lateOldBuildCannotReplaceNewerPublication()throws Exception{
        var s=service(new DemoKnowledgeEmbedding());s.importDocument("vpn","vpn.md","first");var started=new CountDownLatch(1);var release=new CountDownLatch(1);
        var slow=service(t->{started.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){throw new RuntimeException(e);}return new DemoKnowledgeEmbedding().embed(t);});
        var pool=Executors.newSingleThreadExecutor();try{var old=pool.submit(()->slow.importDocument("vpn","vpn.md","older in flight"));assertTrue(started.await(10,TimeUnit.SECONDS));s.importDocument("vpn","vpn.md","newest committed");release.countDown();assertThrows(ExecutionException.class,()->old.get(10,TimeUnit.SECONDS));assertEquals("newest committed",s.detail("vpn").rawText());}finally{release.countDown();pool.shutdownNow();}
    }
    @Test void separateJvmWriterAndReaderRetainRawRevisionAndVector(@org.junit.jupiter.api.io.TempDir java.nio.file.Path folder)throws Exception{
        java.nio.file.Path written=folder.resolve("written.json"),read=folder.resolve("read.json");
        process("write",written);process("read",read);
        assertEquals(java.nio.file.Files.readString(written),java.nio.file.Files.readString(read));
    }
    @Test void differentModelDimensionsNeverMixRankingAndRequireNewRevision(){
        var demo=service(new DemoKnowledgeEmbedding());var first=demo.importDocument("vpn","vpn.md","same raw");
        KnowledgeEmbedding otherModel=new KnowledgeEmbedding(){public float[] embed(String text){return new float[]{.1f,.2f,.3f};}public int dimensions(){return 3;}public String identity(){return "fixture-other-model-v1";}};
        var other=service(otherModel);var migrated=other.importDocument("vpn","vpn.md","same raw");
        assertNotEquals(first.document().activeRevision(),migrated.document().activeRevision());
        assertTrue(demo.search("same raw",5).hits().isEmpty());assertEquals(1,other.search("same raw",5).hits().size());
        demo.importDocument("another","another.md","demo dimension eight");
        assertEquals("another",demo.search("q",5).hits().get(0).documentId());assertEquals("vpn",other.search("q",5).hits().get(0).documentId());
    }
    private void process(String mode,java.nio.file.Path output)throws Exception{
        var process=new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),KnowledgeProcessProbe.class.getName(),tenant,mode,output.toString())
                .redirectErrorStream(true).redirectOutput(output.resolveSibling(mode+".log").toFile()).start();
        try{assertTrue(process.waitFor(30,TimeUnit.SECONDS),"Knowledge process must finish in 30 seconds");assertEquals(0,process.exitValue(),()->"See subprocess log "+output.resolveSibling(mode+".log"));}finally{if(process.isAlive())process.destroyForcibly();}
    }
}
