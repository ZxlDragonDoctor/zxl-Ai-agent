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
 @Test void realProtocolGenerationAndJudgingAreSeparateCalls()throws Exception{response=envelope("{\"snapshotId\":\"s\",\"claims\":[],\"missingInformation\":[]}");var m=model();assertEquals("s",m.generate("question","s",List.of(),new MemoryContext.MemoryView(List.of(),"none",0,0),Duration.ofSeconds(2)).snapshotId());response=envelope("{\"support\":\"INSUFFICIENT\",\"reason\":\"没有依据\"}");assertEquals("INSUFFICIENT",m.judge("q",new AnswerService.Claim("c",List.of("E1")),List.of(),Duration.ofSeconds(2)).support());assertTrue(bodies.get(0).contains("\"model\":\"gen\""));assertTrue(bodies.get(1).contains("\"model\":\"judge\""));assertTrue(bodies.get(0).contains("enable_thinking"));assertTrue(bodies.get(0).contains("reply_language"));assertTrue(bodies.get(0).contains("reply_detail"));assertTrue(bodies.get(0).contains("reply_format"));assertTrue(bodies.get(0).contains("最多3条"));assertTrue(m.identity().endsWith("prompt-v2"));assertTrue(m.judgeIdentity().endsWith("prompt-v1"));}
 @Test void unknownFieldsMalformedAndOversizedRejected()throws Exception{response=envelope("{\"snapshotId\":\"s\",\"claims\":[],\"permission\":true}");assertThrows(IllegalStateException.class,()->model().generate("q","s",List.of(),new MemoryContext.MemoryView(List.of(),"none",0,0),Duration.ofSeconds(2)));response="x".repeat(65537);assertThrows(IllegalStateException.class,()->model().judge("q",new AnswerService.Claim("c",List.of()),List.of(),Duration.ofSeconds(2)));}
 @Test void redirectAndNonHttpsProductionAreRejected(){code=302;response="";assertThrows(IllegalStateException.class,()->model().judge("q",new AnswerService.Claim("c",List.of()),List.of(),Duration.ofSeconds(2)));assertThrows(IllegalArgumentException.class,()->new HttpAnswerModel("http://127.0.0.1/chat","key","m","j","r"));}
 @Test void generationV2RequiresExplicitMissingInformationField()throws Exception{
  response=envelope("{\"snapshotId\":\"s\",\"claims\":[]}");assertThrows(IllegalStateException.class,()->model().generate("q","s",List.of(),new MemoryContext.MemoryView(List.of(),"none",0,0),Duration.ofSeconds(2)));
  response=envelope("{\"snapshotId\":\"s\",\"claims\":[],\"missingInformation\":[\"缺少年假制度。\"]}");assertEquals(List.of("缺少年假制度。"),model().generate("q","s",List.of(),new MemoryContext.MemoryView(List.of(),"none",0,0),Duration.ofSeconds(2)).missingInformation());
 }

}
