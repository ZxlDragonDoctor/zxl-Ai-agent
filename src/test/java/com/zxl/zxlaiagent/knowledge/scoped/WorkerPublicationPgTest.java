package com.zxl.zxlaiagent.knowledge.scoped;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import com.zxl.zxlaiagent.security.*;
import static org.junit.jupiter.api.Assertions.*;
class WorkerPublicationPgTest {
 ScopedKnowledgePgTest fixture;
 @BeforeEach void setup(){fixture=new ScopedKnowledgePgTest();fixture.setup();}
 @AfterEach void cleanup(){fixture.cleanup();}
 MockHttpServletRequest request() {
  String path=WorkerRequestContract.BASE+"/documents";
  var request=new MockHttpServletRequest("GET",path);
  request.addHeader("Authorization","Bearer abcdefghijklmnopqrstuvwxyz_0123456789-ABCDEFGHIJKLMNO");
  request.addHeader("X-Service-Ticket",new ServiceCallAuthorizer(fixture.security,fixture.authorization).issue(
    fixture.alice,WorkerRequestContract.AUDIENCE,"knowledge.read",null,WorkerRequestContract.hash("GET",path,null,new byte[0])));
  return request;
 }
 WorkerAuthenticationFilter filter(){return new WorkerAuthenticationFilter(new ServiceCallAuthorizer(fixture.security,fixture.authorization),fixture.authorization,"abcdefghijklmnopqrstuvwxyz_0123456789-ABCDEFGHIJKLMNO");}
 @Test void writerContentIsFlushedBeforePublication() throws Exception {
  var response=new MockHttpServletResponse();
  filter().doFilter(request(),response,(input,output)->output.getWriter().write("safe writer content"));
  assertEquals("safe writer content",response.getContentAsString());
 }
 @Test void oversizedControllerResponseIsDiscarded() throws Exception {
  var response=new MockHttpServletResponse();
  filter().doFilter(request(),response,(input,output)->output.getOutputStream().write(new byte[1024*1024+1]));
  assertEquals(502,response.getStatus());
  assertTrue(response.getContentAsString().contains("WORKER_RESPONSE_TOO_LARGE"));
  assertTrue(response.getContentAsByteArray().length<1024);
 }
 @Test void slowSocketDoesNotHoldAuthorizationEpoch() throws Exception {
  var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
  var response=new MockHttpServletResponse(){
   @Override public jakarta.servlet.ServletOutputStream getOutputStream(){return new jakarta.servlet.ServletOutputStream(){
    public boolean isReady(){return true;}public void setWriteListener(jakarta.servlet.WriteListener listener){}
    public void write(int value)throws java.io.IOException{entered.countDown();try{if(!release.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new java.io.IOException("test timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new java.io.IOException(e);}}
   };}
  };
  var filter=filter();var request=request();var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
  var sending=pool.submit(()->{filter.doFilter(request,response,(input,output)->output.getOutputStream().write(65));return null;});
  try {
   assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
   pool.submit(()->fixture.auth.revoke(fixture.alice)).get(1,java.util.concurrent.TimeUnit.SECONDS);
  } finally {release.countDown();sending.get(5,java.util.concurrent.TimeUnit.SECONDS);pool.shutdownNow();}
 }
 @Test void revocationAfterControllerResponseDiscardsBufferedSecret()throws Exception{
  String token="abcdefghijklmnopqrstuvwxyz_0123456789-ABCDEFGHIJKLMNO",path=WorkerRequestContract.BASE+"/documents";var tickets=new ServiceCallAuthorizer(fixture.security,fixture.authorization);var filter=new WorkerAuthenticationFilter(tickets,fixture.authorization,token);var request=new MockHttpServletRequest("GET",path);request.addHeader("Authorization","Bearer "+token);request.addHeader("X-Service-Ticket",tickets.issue(fixture.alice,WorkerRequestContract.AUDIENCE,"knowledge.read",null,WorkerRequestContract.hash("GET",path,null,new byte[0])));var response=new MockHttpServletResponse();
  filter.doFilter(request,response,(input,output)->{output.getOutputStream().write("private unpublished secret".getBytes());fixture.auth.revoke(fixture.alice);});
  assertEquals(401,response.getStatus());assertFalse(response.getContentAsString().contains("private unpublished secret"));assertTrue(response.getContentAsString().contains("UNAUTHENTICATED"));
 }
}
