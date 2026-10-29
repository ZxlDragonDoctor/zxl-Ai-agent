package com.zxl.zxlaiagent.knowledge.answer;
import org.junit.jupiter.api.Test;
import java.util.concurrent.TimeoutException;
import static org.junit.jupiter.api.Assertions.*;
class AnswerFailureDiagnosticsTest {
 private Object summary(Throwable failure)throws Exception{return Class.forName("com.zxl.zxlaiagent.knowledge.answer.AnswerFailureDiagnostics").getDeclaredMethod("summarize",Throwable.class).invoke(null,failure);}
 @Test void nestedTimeoutIsClassifiedWithoutExposingPrivateMessages()throws Exception{
  String result=summary(new IllegalStateException("private-api-key-and-query",new TimeoutException("private-provider-body"))).toString();
  assertTrue(result.contains("TIMEOUT"));assertFalse(result.contains("private-"));
 }
 @Test void knownContractCodeIsRetainedButArbitraryMessagesAreNot()throws Exception{
  assertTrue(summary(new IllegalStateException("INVALID_GENERATION_FORMAT_OR_SNAPSHOT")).toString().contains("INVALID_GENERATION_FORMAT_OR_SNAPSHOT"));
  assertFalse(summary(new IllegalStateException("sk-private-secret-and-user-material")).toString().contains("sk-private"));
 }
 @Test void cyclicExceptionGraphsAreBounded()throws Exception{
  var first=new IllegalStateException("private-a");var second=new IllegalStateException("private-b",first);first.initCause(second);
  assertTrue(summary(first).toString().length()<1000);
 }
}
