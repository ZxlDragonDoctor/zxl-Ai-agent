package com.zxl.zxlaiagent.knowledge.gateway;
import com.zxl.zxlaiagent.security.*;
import com.zxl.zxlaiagent.knowledge.scoped.WorkerRequestContract;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.Duration;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.AdditionalMatchers.aryEq;
class FormalKnowledgeGatewayControllerTest {
 @Test void anonymousRequestsCannotReachInternalClient()throws Exception{var client=mock(ScopedKnowledgeClient.class);var mvc=MockMvcBuilders.standaloneSetup(new FormalKnowledgeGatewayController(client)).build();mvc.perform(get(WorkerRequestContract.BASE+"/documents")).andExpect(status().isUnauthorized());verifyNoInteractions(client);}
 @Test void verifiedActorContextPathAndOriginalBodyArePassedExplicitly()throws Exception{var client=mock(ScopedKnowledgeClient.class);var mvc=MockMvcBuilders.standaloneSetup(new FormalKnowledgeGatewayController(client)).build();var actor=new AuthenticatedActor("subject","tenant","session",1,"knowledge-maintainer",1);String body="{ \"documentId\":\"sample\", \"content\":\"raw text\" }",path=WorkerRequestContract.BASE+"/documents";byte[] bytes=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);when(client.exchange(eq(actor.ref()),eq("POST"),eq(path),isNull(),aryEq(bytes),eq(Duration.ofSeconds(30)))).thenReturn(new ScopedKnowledgeClient.Response(200,"{\"outcome\":\"IMPORTED\"}".getBytes()));mvc.perform(post("/api"+path).contextPath("/api").requestAttr("com.zxl.zxlaiagent.security.web.AuthenticatedRequest.actor",actor).header("Authorization","Bearer browser-supplied-value").contentType("application/json").content(bytes)).andExpect(status().isOk()).andExpect(content().json("{\"outcome\":\"IMPORTED\"}"));verify(client).exchange(eq(actor.ref()),eq("POST"),eq(path),isNull(),aryEq(bytes),eq(Duration.ofSeconds(30)));}
 @Test void unmappedEvaluationDoesNotDispatch()throws Exception{var client=mock(ScopedKnowledgeClient.class);var mvc=MockMvcBuilders.standaloneSetup(new FormalKnowledgeGatewayController(client)).build();mvc.perform(post(WorkerRequestContract.BASE+"/evaluation/run")).andExpect(status().isNotFound());verifyNoInteractions(client);}
 @Test void clientFailuresRetainSafeStatusAndCode()throws Exception{var client=mock(ScopedKnowledgeClient.class);var mvc=MockMvcBuilders.standaloneSetup(new FormalKnowledgeGatewayController(client)).build();var actor=new AuthenticatedActor("subject","tenant","session",1,"employee",1);when(client.exchange(any(),any(),any(),any(),any(),any())).thenThrow(new SecurityFailure("KNOWLEDGE_WORKER_TIMEOUT",504));mvc.perform(get(WorkerRequestContract.BASE+"/documents").requestAttr("com.zxl.zxlaiagent.security.web.AuthenticatedRequest.actor",actor)).andExpect(status().isGatewayTimeout()).andExpect(jsonPath("$.code").value("KNOWLEDGE_WORKER_TIMEOUT"));}
}
