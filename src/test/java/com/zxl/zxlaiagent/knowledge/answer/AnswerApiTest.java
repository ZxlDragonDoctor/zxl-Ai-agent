package com.zxl.zxlaiagent.knowledge.answer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class AnswerApiTest {
 @Test void unknownScopeAndWrongTypesAreBadRequest()throws Exception{
  var service=mock(AnswerService.class);var mvc=MockMvcBuilders.standaloneSetup(new AnswerController(service)).build();
  mvc.perform(post("/ai/learning/knowledge/answers").contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"VPN 809\",\"conversationId\":\"c\",\"mode\":\"demo\",\"budgetMs\":10000,\"tenant\":\"another\"}")).andExpect(status().isBadRequest());
  mvc.perform(post("/ai/learning/knowledge/answers").contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"VPN 809\",\"conversationId\":\"c\",\"mode\":\"demo\",\"budgetMs\":\"10000\"}")).andExpect(status().isBadRequest());verifyNoInteractions(service);
 }
 @Test void snapshotFixturesAreFixedAndRuleDemoIsNotSemanticGate(){assertEquals(12,SupportFixtures.cases().size());assertEquals(6,SupportFixtures.cases().stream().filter(c->c.split().equals("holdout")).count());var report=SupportFixtures.run(AnswerModel.demo(),java.time.Duration.ofSeconds(1));assertFalse(report.holdoutGatePassed());assertTrue(report.unsupportedAccepted()>0);}
 @Test void partialResponsePublishesMissingSubquestionExplanation()throws Exception{
  var service=mock(AnswerService.class);when(service.answer(any())).thenReturn(new AnswerService.Response("trace","snapshot","PARTIAL","VPN 809 检查端口 [E1]",java.util.List.of(),java.util.List.of(),"MISSING_SUBQUESTION_EVIDENCE",java.util.List.of("缺少年假制度资料。"),"gen-v2","judge-v1",10,0,new MemoryContext.MemoryView(java.util.List.of(),"alice",0,0)));
  var mvc=MockMvcBuilders.standaloneSetup(new AnswerController(service)).build();mvc.perform(post("/ai/learning/knowledge/answers").contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"VPN 809和年假\",\"conversationId\":\"c\",\"mode\":\"real\",\"budgetMs\":10000}")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PARTIAL")).andExpect(jsonPath("$.missingInformation[0]").value("缺少年假制度资料。"));
 }

}
