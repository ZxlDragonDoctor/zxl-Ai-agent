package com.zxl.zxlaiagent.knowledge.scoped;

import com.zxl.zxlaiagent.knowledge.gateway.ScopedKnowledgeClient;
import com.zxl.zxlaiagent.security.*;
import org.junit.jupiter.api.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Actual dedicated Worker JVM + PostgreSQL; deterministic demo embedding, no supplier/model request. */
class FormalWorkerRecoveryPgTest {
 ScopedKnowledgePgTest fixture;
 Process worker;
 @BeforeEach void setup(){fixture=new ScopedKnowledgePgTest();fixture.setup();}
 @AfterEach void cleanup()throws Exception{try{stop();}finally{fixture.cleanup();}}
 @Test void sameGatewayRecoversAtSamePortWithOldTenantDataAndNewWrites()throws Exception{
  int port;try(var socket=new ServerSocket(0)){port=socket.getLocalPort();}
  byte[] random=new byte[48];new java.security.SecureRandom().nextBytes(random);
  String token=Base64.getUrlEncoder().withoutPadding().encodeToString(random);
  var gateway=new ScopedKnowledgeClient(URI.create("http://127.0.0.1:"+port),token,new ServiceCallAuthorizer(fixture.security,fixture.authorization),fixture.authorization);
  start(port,token,"before");
  write(gateway,fixture.alice,"same","alpha persisted policy");write(gateway,fixture.bob,"same","beta persisted policy");
  assertDetail(gateway,fixture.alice,"same","alpha persisted policy","beta persisted policy");
  assertDetail(gateway,fixture.bob,"same","beta persisted policy","alpha persisted policy");
  long firstPid=worker.pid();stop();
  var unavailable=assertThrows(SecurityFailure.class,()->gateway.exchange(fixture.alice,"GET",WorkerRequestContract.BASE+"/documents/same",null,new byte[0],Duration.ofSeconds(3)));
  assertEquals("KNOWLEDGE_WORKER_UNAVAILABLE",unavailable.code());assertEquals(503,unavailable.status());
  // Query authoritative PG storage while HTTP is down; failure must not mutate persisted knowledge.
  assertEquals("alpha persisted policy",fixture.factory.knowledge(fixture.alice).detail("same").rawText());
  assertEquals("beta persisted policy",fixture.factory.knowledge(fixture.bob).detail("same").rawText());
  start(port,token,"after");assertNotEquals(firstPid,worker.pid());
  // Reuse the exact gateway/HttpClient; never replace it or fall back to a local store.
  assertDetail(gateway,fixture.alice,"same","alpha persisted policy","beta persisted policy");
  assertDetail(gateway,fixture.bob,"same","beta persisted policy","alpha persisted policy");
  write(gateway,fixture.alice,"new","alpha recovered write");write(gateway,fixture.bob,"new","beta recovered write");
  assertDetail(gateway,fixture.alice,"new","alpha recovered write","beta recovered write");
  assertDetail(gateway,fixture.bob,"new","beta recovered write","alpha recovered write");
  assertEquals("alpha recovered write",fixture.factory.knowledge(fixture.alice).detail("new").rawText());
  assertEquals("beta recovered write",fixture.factory.knowledge(fixture.bob).detail("new").rawText());
 }
 private void write(ScopedKnowledgeClient gateway,ActorRef actor,String id,String content){
  byte[] body=("{\"documentId\":\""+id+"\",\"sourceName\":\"recovery.md\",\"content\":\""+content+"\"}").getBytes(StandardCharsets.UTF_8);
  assertEquals(200,gateway.exchange(actor,"POST",WorkerRequestContract.BASE+"/documents",null,body,Duration.ofSeconds(10)).status());
 }
 private void assertDetail(ScopedKnowledgeClient gateway,ActorRef actor,String id,String expected,String foreign){
  var response=gateway.exchange(actor,"GET",WorkerRequestContract.BASE+"/documents/"+id,null,new byte[0],Duration.ofSeconds(10));
  assertEquals(200,response.status());String text=new String(response.body(),StandardCharsets.UTF_8);assertTrue(text.contains(expected));assertFalse(text.contains(foreign));
 }
 private void start(int port,String token,String phase)throws Exception{
  assertNull(worker,"only this test's exited worker may be replaced");
  var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),FormalKnowledgeWorker.class.getName())
      .redirectErrorStream(true).redirectOutput(Path.of("target/lesson26-worker-recovery-"+phase+".log").toFile());
  builder.environment().put("LESSON17_INTERNAL_SERVICE_TOKEN",token);builder.environment().put("LESSON17_WORKER_PORT",Integer.toString(port));builder.environment().put("LESSON12_EMBEDDING_MODE","demo");builder.environment().put("LESSON13_RERANK_MODE","rule");
  worker=builder.start();var http=HttpClient.newBuilder().connectTimeout(Duration.ofMillis(300)).build();
  long end=System.nanoTime()+Duration.ofSeconds(30).toNanos();
  while(System.nanoTime()<end){assertTrue(worker.isAlive(),"Worker exited; inspect private target recovery log");
   try{var response=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+WorkerRequestContract.BASE+"/documents")).timeout(Duration.ofSeconds(1)).GET().build(),HttpResponse.BodyHandlers.discarding());if(response.statusCode()==401)return;}
   catch(java.io.IOException unavailable){}Thread.sleep(100);
  }
  fail("Worker did not become authenticated HTTP-ready within 30s");
 }
 private void stop()throws Exception{
  if(worker==null)return;worker.destroy();if(!worker.waitFor(5,TimeUnit.SECONDS)){worker.destroyForcibly();assertTrue(worker.waitFor(5,TimeUnit.SECONDS),"test-owned Worker must terminate");}
  assertFalse(worker.isAlive());worker=null;
 }
}
