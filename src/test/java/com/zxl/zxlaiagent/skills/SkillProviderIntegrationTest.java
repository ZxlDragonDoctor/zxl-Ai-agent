package com.zxl.zxlaiagent.skills;

import com.zxl.zxlaiagent.conversation.agent.*;
import com.zxl.zxlaiagent.research.ResearchRunner;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SkillProviderIntegrationTest {
 static com.fasterxml.jackson.databind.JsonNode json(String value) { try { return new com.fasterxml.jackson.databind.ObjectMapper().readTree(value); } catch(Exception e) { throw new AssertionError(e); } }
 @Test void explicitVersionIsPinnedInPublicEventsAndClarificationDoesNotCallResearch() {
  var registry=new SkillRegistry(SkillCatalogue.builtIns());
  var gateway=mock(ResearchRunner.Gateway.class);var model=mock(ResearchRunner.Model.class);var verifier=mock(ResearchRunner.Verifier.class);
  var runner=new SkillRunner(registry,new SkillExecutionPolicy(Set.of("knowledge__knowledge_lookup_v1")),gateway,model,verifier);
  var provider=new ItTicketSkillProvider(runner,registry);
  var agent=provider.create(new AgentRunOptions("it-ticket-analysis","v2"));
  registry.setDefault("it-ticket-analysis","v1");
  var events=new ArrayList<com.zxl.zxlaiagent.agent.model.AgentExecutionEvent>();agent.setEventObserver(events::add);
  String answer=agent.run("VPN连接失败，请帮忙分析");
  assertTrue(answer.contains("错误码"));
  var selected=events.stream().filter(e->e.type().equals("SKILL_SELECTED")).findFirst().orElseThrow();
  assertEquals("v2",json(selected.content()).path("definition").path("version").asText());
  assertTrue(events.stream().anyMatch(e->e.type().equals("SKILL_TERMINAL")&&json(e.content()).path("status").asText().equals("NEEDS_INPUT")));
  verifyNoInteractions(gateway,model,verifier);
  assertEquals(com.zxl.zxlaiagent.agent.model.AgentEndReason.ANSWER_READY,agent.getEndReason());
 }
 @Test void nonexistentOrDisabledVersionFailsBeforeAgentCreation() {
  var registry=new SkillRegistry(SkillCatalogue.builtIns());var provider=new ItTicketSkillProvider(mock(SkillRunner.class),registry);
  assertThrows(com.zxl.zxlaiagent.conversation.ConversationException.class,()->provider.create(new AgentRunOptions("it-ticket-analysis","v99")));
  registry.setEnabled("it-ticket-analysis","v2",false);
  assertThrows(com.zxl.zxlaiagent.conversation.ConversationException.class,()->provider.create(new AgentRunOptions("it-ticket-analysis","v2")));
 }
}
