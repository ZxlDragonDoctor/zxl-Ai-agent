package com.zxl.zxlaiagent.research;

import com.zxl.zxlaiagent.agent.stream.DashScopeAgentStreamer;
import com.zxl.zxlaiagent.usage.ModelCallBudget;
import com.zxl.zxlaiagent.security.execution.OperationBoundary;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class StreamingResearchBudgetWiringTest {
 @Test void selectionAndAnswerBothRequestFreshTrustedAccounting() {
  var streamer=mock(DashScopeAgentStreamer.class);var boundary=OperationBoundary.unrestricted();
  var budget=mock(ModelCallBudget.class);var requests=new AtomicInteger();
  when(streamer.stream(anyString(),anyList(),any(),any(),any(),same(boundary),same(budget)))
    .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("answer")))));
  var model=new StreamingResearchModel(streamer,boundary,()->{requests.incrementAndGet();return budget;});
  assertThrows(IllegalStateException.class,()->model.select("question",List.of(),Duration.ofSeconds(1),()->false));
  assertEquals("answer",model.answer("question",List.of(),Duration.ofSeconds(1),()->false));
  assertEquals(2,requests.get());
  verify(streamer,times(2)).stream(anyString(),anyList(),any(),any(),any(),same(boundary),same(budget));
 }
}
