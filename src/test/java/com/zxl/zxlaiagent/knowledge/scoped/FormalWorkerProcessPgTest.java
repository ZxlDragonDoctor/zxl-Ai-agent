package com.zxl.zxlaiagent.knowledge.scoped;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import com.zxl.zxlaiagent.security.*;
/** Real independently booted HTTP worker, real PG authority/storage, disclosed deterministic embedding. */
class FormalWorkerProcessPgTest {
 ScopedKnowledgePgTest fixture;
 @BeforeEach void setup(){fixture=new ScopedKnowledgePgTest();fixture.setup();}
 @AfterEach void cleanup(){fixture.cleanup();}
 @Test void explicitWorkerBootAuthenticatesInternalHttpAndKeepsTenantResults()throws Exception{
  int port;try(var socket=new java.net.ServerSocket(0)){port=socket.getLocalPort();}
  byte[] random=new byte[48];new java.security.SecureRandom().nextBytes(random);String token=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(random);
  var builder=new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),FormalKnowledgeWorker.class.getName()).redirectErrorStream(true).redirectOutput(new java.io.File("target/lesson17-worker-process.log"));
  builder.environment().put("LESSON17_INTERNAL_SERVICE_TOKEN",token);builder.environment().put("LESSON17_WORKER_PORT",Integer.toString(port));builder.environment().put("LESSON12_EMBEDDING_MODE","demo");builder.environment().put("LESSON13_RERANK_MODE","rule");var process=builder.start();var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();String base="http://127.0.0.1:"+port;String path=WorkerRequestContract.BASE+"/documents";
  try{
   long end=System.nanoTime()+Duration.ofSeconds(30).toNanos();boolean ready=false;while(System.nanoTime()<end){assertTrue(process.isAlive(),"See target/lesson17-worker-process.log");try{if(client.send(HttpRequest.newBuilder(URI.create(base+path)).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()==401){ready=true;break;}}catch(java.io.IOException unavailable){}Thread.sleep(100);}assertTrue(ready,"Worker must boot within 30s");
   assertEquals(401,client.send(HttpRequest.newBuilder(URI.create(base+path)).header("Authorization","Bearer "+token).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());
   var tickets=new ServiceCallAuthorizer(fixture.security,fixture.authorization);
   for(var actor:java.util.List.of(fixture.alice,fixture.bob)){String value=actor.equals(fixture.alice)?"alpha worker secret":"beta worker secret";byte[] body=("{\"documentId\":\"same\",\"sourceName\":\"worker.md\",\"content\":\""+value+"\"}").getBytes(StandardCharsets.UTF_8);String ticket=tickets.issue(actor,WorkerRequestContract.AUDIENCE,"knowledge.manage",null,WorkerRequestContract.hash("POST",path,null,body));var response=client.send(HttpRequest.newBuilder(URI.create(base+path)).header("Authorization","Bearer "+token).header("X-Service-Ticket",ticket).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,response.statusCode(),response.body());}
   String detail=path+"/same";String ticket=tickets.issue(fixture.bob,WorkerRequestContract.AUDIENCE,"knowledge.read",null,WorkerRequestContract.hash("GET",detail,null,new byte[0]));var request=HttpRequest.newBuilder(URI.create(base+detail)).header("Authorization","Bearer "+token).header("X-Service-Ticket",ticket).GET().build();var response=client.send(request,HttpResponse.BodyHandlers.ofString());assertEquals(200,response.statusCode(),response.body());assertTrue(response.body().contains("beta worker secret"));assertFalse(response.body().contains("alpha worker secret"));assertEquals(401,client.send(request,HttpResponse.BodyHandlers.ofString()).statusCode());
  }finally{process.destroy();if(!process.waitFor(5,TimeUnit.SECONDS))process.destroyForcibly();}
 }
}
