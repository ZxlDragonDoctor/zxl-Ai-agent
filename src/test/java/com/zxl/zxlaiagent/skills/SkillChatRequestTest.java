package com.zxl.zxlaiagent.skills;

import com.zxl.zxlaiagent.controller.AiController;
import com.zxl.zxlaiagent.conversation.agent.AgentRunOptions;
import com.zxl.zxlaiagent.conversation.controller.ConversationExceptionHandler;
import com.zxl.zxlaiagent.conversation.service.AgentConversationService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SkillChatRequestTest {
 @Test void newSelectionReachesProviderWithoutIdentityAndLegacyRequestKeepsOriginalPath() throws Exception {
  var service=mock(AgentConversationService.class);var controller=new AiController();ReflectionTestUtils.setField(controller,"agentConversations",service);
  var mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ConversationExceptionHandler()).build();
  mvc.perform(post("/ai/agent/chat").contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"VPN 401\",\"conversationId\":\"c1\",\"skillId\":\"it-ticket-analysis\",\"skillVersion\":\"v2\",\"tenant\":\"forged\"}")).andExpect(status().isOk());
  verify(service).chat("VPN 401","c1",new AgentRunOptions("it-ticket-analysis","v2"));
  mvc.perform(post("/ai/agent/chat").contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"hello\",\"conversationId\":\"c2\"}")).andExpect(status().isOk());
  verify(service).chat("hello","c2");
 }
 @Test void invalidSelectionIsAClientErrorNotAnInternalFailure() throws Exception {
  var service=mock(AgentConversationService.class);var controller=new AiController();ReflectionTestUtils.setField(controller,"agentConversations",service);
  var mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ConversationExceptionHandler()).build();
  mvc.perform(post("/ai/agent/chat").contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"VPN 401\",\"skillId\":\"../../path\",\"skillVersion\":\"v1\"}")).andExpect(status().isBadRequest());
  verifyNoInteractions(service);
 }
}
