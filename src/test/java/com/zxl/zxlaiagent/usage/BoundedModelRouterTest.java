package com.zxl.zxlaiagent.usage;

import com.zxl.zxlaiagent.security.SecurityFailure;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import java.util.ArrayList;
import java.util.concurrent.CancellationException;
import static org.junit.jupiter.api.Assertions.*;

class BoundedModelRouterTest {
 private RuntimeException http(int status) {
  return new IllegalStateException("provider failure",WebClientResponseException.create(status,"fixture",HttpHeaders.EMPTY,new byte[0],null));
 }
 @Test void transientFailureCanUseOneDistinctConfiguredFallback() {
  for(int status:new int[]{429,503}) {
   var seen=new ArrayList<String>();
   String result=new BoundedModelRouter("primary","secondary").execute((model,index)->{
    seen.add(model);if(index==0)throw http(status);return "answer";
   },()->false,()->false);
   assertEquals("answer",result);assertEquals(java.util.List.of("primary","secondary"),seen);
  }
 }
 @Test void safetyCancellationOutputAndNonTransientFailuresNeverFallback() {
  RuntimeException accountingFailure=http(503);accountingFailure.addSuppressed(new IllegalStateException("Accounting failed"));
  for(RuntimeException failure:new RuntimeException[]{http(401),http(400),new SecurityFailure("ACTION_DENIED",403),new CancellationException(),new IllegalStateException("HTTP 503"),accountingFailure}) {
   var calls=new java.util.concurrent.atomic.AtomicInteger();
   assertThrows(RuntimeException.class,()->new BoundedModelRouter("primary","secondary").execute((model,index)->{calls.incrementAndGet();throw failure;},()->false,()->false));
   assertEquals(1,calls.get());
  }
  var calls=new java.util.concurrent.atomic.AtomicInteger();
  assertThrows(RuntimeException.class,()->new BoundedModelRouter("primary","secondary").execute((m,i)->{calls.incrementAndGet();throw http(503);},()->false,()->true));
  assertEquals(1,calls.get());
 }
 @Test void aSecondFailureIsTerminalAndCancellationBeforeDispatchMakesNoCall() {
  var calls=new java.util.concurrent.atomic.AtomicInteger();
  assertThrows(RuntimeException.class,()->new BoundedModelRouter("primary","secondary").execute((m,i)->{calls.incrementAndGet();throw http(503);},()->false,()->false));
  assertEquals(2,calls.get());calls.set(0);
  assertThrows(CancellationException.class,()->new BoundedModelRouter("primary","secondary").execute((m,i)->{calls.incrementAndGet();return "bad";},()->true,()->false));
  assertEquals(0,calls.get());assertThrows(IllegalArgumentException.class,()->new BoundedModelRouter("same","same"));
 }
}
