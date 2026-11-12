package com.zxl.zxlaiagent.knowledge.answer;
import org.junit.jupiter.api.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class HttpAnswerModelTest {
 HttpServer server;String response;int code=200;List<String> bodies;
 @BeforeEach void setup()throws Exception{bodies=new ArrayList<>();server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/chat",x->{bodies.add(new String(x.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));byte[] body=response.getBytes(java.nio.charset.StandardCharsets.UTF_8);x.sendResponseHeaders(code,body.length);x.getResponseBody().write(body);x.close();});server.start();}
 @AfterEach void stop(){server.stop(0);}
 HttpAnswerModel model(){return new HttpAnswerModel(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/chat"),"test-only","gen","judge","r1",HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),true);}
 String envelope(String content)throws Exception{return "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":"+new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(content)+"}}]}";}
 @Test void realProtocolGenerationAndJudgingAreSeparateCalls()throws Exception{response=envelope("{\"snapshotId\":\"s\",\"claims\":[],\"missingInformation\":[]}");var m=model();assertEquals("s",m.generate("question","s",List.of(),new MemoryContext.MemoryView(List.of(),"none",0,0),Duration.ofSeconds(2)).snapshotId());response=envelope("{\"support\":\"INSUFFICIENT\",\"reason\":\"没有依据\"}");assertEquals("INSUFFICIENT",m.judge("q",new AnswerService.Claim("c",List.of("E1")),List.of(),Duration.ofSeconds(2)).support());assertTrue(bodies.get(0).contains("\"model\":\"gen\""));assertTrue(bodies.get(1).contains("\"model\":\"judge\""));assertTrue(bodies.get(0).contains("enable_thinking"));assertTrue(bodies.get(0).contains("reply_language"));assertTrue(bodies.get(0).contains("reply_detail"));assertTrue(bodies.get(0).contains("reply_format"));assertTrue(bodies.get(0).contains("最多3条"));assertTrue(m.identity().endsWith("prompt-v3"));assertTrue(m.judgeIdentity().endsWith("prompt-v2"));}
 @Test void unknownFieldsMalformedAndOversizedRejected()throws Exception{response=envelope("{\"snapshotId\":\"s\",\"claims\":[],\"permission\":true}");assertThrows(IllegalStateException.class,()->model().generate("q","s",List.of(),new MemoryContext.MemoryView(List.of(),"none",0,0),Duration.ofSeconds(2)));response="x".repeat(65537);assertThrows(IllegalStateException.class,()->model().judge("q",new AnswerService.Claim("c",List.of()),List.of(),Duration.ofSeconds(2)));}
 @Test void redirectAndNonHttpsProductionAreRejected(){code=302;response="";assertThrows(IllegalStateException.class,()->model().judge("q",new AnswerService.Claim("c",List.of()),List.of(),Duration.ofSeconds(2)));assertThrows(IllegalArgumentException.class,()->new HttpAnswerModel("http://127.0.0.1/chat","key","m","j","r"));}
 @Test void modelReceivesOnlyRequestLocalEvidenceAndAllowedPreferences() throws Exception {
  var evidence = new AnswerService.Evidence("E1", "snapshot", "internal-doc", "internal-revision",
   "internal-chunk", "internal-source", 0, 4, 1, 1, "正文", "internal-hash", true, 12);
  var memory = new MemoryContext.MemoryView(List.of(
   new MemoryContext.Fact("internal-fact", "reply_language", "zh", 1),
   new MemoryContext.Fact("internal-fact2", "reply_detail", "internal-value", 2),
   new MemoryContext.Fact("internal-fact3", "role", "admin", 3)), "internal-tenant/internal-user", 50, 2);
  var model = model();
  response = envelope("{\"snapshotId\":\"snapshot\",\"claims\":[],\"missingInformation\":[]}");
  model.generate("question", "snapshot", List.of(evidence), memory, Duration.ofSeconds(2));
  response = envelope("{\"support\":\"SUPPORTED\",\"reason\":\"ok\"}");
  model.judge("question", new AnswerService.Claim("claim", List.of("E1")), List.of(evidence), Duration.ofSeconds(2));
  var json = new com.fasterxml.jackson.databind.ObjectMapper();
  var expected = json.readTree("{\"id\":\"E1\",\"text\":\"正文\",\"truncated\":true}");
  for (String body : bodies) {
   var input = json.readTree(json.readTree(body).at("/messages/1/content").asText());
   assertEquals(expected, input.at("/evidence/0"));
   assertFalse(input.toString().contains("internal-"));
  }
  var generation = json.readTree(json.readTree(bodies.get(0)).at("/messages/1/content").asText());
  assertEquals(json.readTree("{\"facts\":[{\"key\":\"reply_language\",\"value\":\"zh\"}]}"), generation.path("memory"));
 }
 @Test void incompleteBodyKeepsTheOriginalDeadline() throws Exception {
  var headersSent = new java.util.concurrent.CountDownLatch(1);
  var release = new java.util.concurrent.CountDownLatch(1);
  server.removeContext("/chat");
  server.createContext("/chat", exchange -> {
   try (exchange) {
    exchange.getRequestBody().readAllBytes();
    exchange.sendResponseHeaders(200, 0);
    exchange.getResponseBody().write('{');
    exchange.getResponseBody().flush();
    headersSent.countDown();
    try { release.await(4, java.util.concurrent.TimeUnit.SECONDS); }
    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
   }
  });
  var client = model();
  try {
   assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
    assertThrows(IllegalStateException.class, () -> client.judge("q",
     new AnswerService.Claim("c", List.of()), List.of(), Duration.ofSeconds(1))));
   assertEquals(0, headersSent.getCount());
  } finally { release.countDown(); }
 }
 @Test void generationV2RequiresExplicitMissingInformationField()throws Exception{
  response=envelope("{\"snapshotId\":\"s\",\"claims\":[]}");assertThrows(IllegalStateException.class,()->model().generate("q","s",List.of(),new MemoryContext.MemoryView(List.of(),"none",0,0),Duration.ofSeconds(2)));
  response=envelope("{\"snapshotId\":\"s\",\"claims\":[],\"missingInformation\":[\"缺少年假制度。\"]}");assertEquals(List.of("缺少年假制度。"),model().generate("q","s",List.of(),new MemoryContext.MemoryView(List.of(),"none",0,0),Duration.ofSeconds(2)).missingInformation());
 }

}
