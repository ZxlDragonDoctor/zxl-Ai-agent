package com.zxl.zxlaiagent.skills;

import com.zxl.zxlaiagent.conversation.agent.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SkillApiTest {
 @Test void directoryExposesVersionsWithoutAllowingUntrustedPublication() throws Exception {
  var registry=new SkillRegistry(SkillCatalogue.builtIns());
  var mvc=MockMvcBuilders.standaloneSetup(new SkillController(registry)).build();
  mvc.perform(get("/ai/learning/skills")).andExpect(status().isOk()).andExpect(jsonPath("$.defaultVersion").value("v1")).andExpect(jsonPath("$.versions.length()").value(2)).andExpect(jsonPath("$.administrationEnabled").value(false));
  mvc.perform(get("/ai/learning/skills/it-ticket-analysis/v2")).andExpect(status().isOk()).andExpect(jsonPath("$.definition.version").value("v2"));
  mvc.perform(get("/ai/learning/skills/it-ticket-analysis/unknown")).andExpect(status().isNotFound());
  mvc.perform(post("/ai/learning/skills/it-ticket-analysis/v2")).andExpect(status().isMethodNotAllowed());
  assertEquals("v1",registry.select("it-ticket-analysis",null).version());
 }
 @Test void optionsAreBoundedAndCannotBeUsedOnAnUnrelatedProvider() {
  assertThrows(IllegalArgumentException.class,()->new AgentRunOptions("../../file","v1"));
  assertThrows(IllegalArgumentException.class,()->new AgentRunOptions("it-ticket-analysis","x".repeat(80)));
  ConversationAgentProvider ordinary=new ConversationAgentProvider(){public AgentDefinition definition(){return null;}public com.zxl.zxlaiagent.agent.ToolCallAgent create(){throw new AssertionError("Must reject before creation");}};
  assertThrows(IllegalArgumentException.class,()->ordinary.create(new AgentRunOptions("it-ticket-analysis","v1")));
 }
}
