package com.zxl.zxlaiagent.knowledge.memory;
import com.zxl.zxlaiagent.knowledge.KnowledgeDatabase;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class MemoryApiPgTest {
 MockMvc api;String testTenant="memory_api_"+java.util.UUID.randomUUID().toString().replace("-","");
 @BeforeEach void setup(){var db=new KnowledgeDatabase(System.getenv("LESSON12_DB_URL"),System.getenv("LESSON12_DB_USER"),System.getenv("LESSON12_DB_PASSWORD"));api=MockMvcBuilders.standaloneSetup(new MemoryController(db,java.time.Clock.systemUTC(), scope -> new MemoryService.Scope(testTenant,scope.userId()))).build();}
 @Test void identityFieldsAndUnregisteredProfilesAreRejected()throws Exception{api.perform(post("/ai/learning/knowledge/memories/proposals").contentType("application/json").content("{\"tenantId\":\"evil\",\"userText\":\"我偏好中文回复\"}")).andExpect(status().isBadRequest());api.perform(get("/ai/learning/knowledge/memories").param("profile","administrator")).andExpect(status().isBadRequest());}
 @Test void wrongTypesAndOversizedBudgetsAreRejected()throws Exception{api.perform(post("/ai/learning/knowledge/memories/x/confirm").contentType("application/json").content("{\"version\":\"1\"}")).andExpect(status().isBadRequest());api.perform(get("/ai/learning/knowledge/memories/recall").param("maxChars","2001")).andExpect(status().isBadRequest());}
 @Test void missingMemoryIs404WithoutOtherScopeDisclosure()throws Exception{api.perform(post("/ai/learning/knowledge/memories/not-existing/confirm").contentType("application/json").content("{\"profile\":\"bob\",\"version\":1}")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MEMORY_NOT_FOUND"));}
 @Test void lifecycleApiDatesAreIsoInstantsIncludingRecall()throws Exception{
  var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
  var proposal=api.perform(post("/ai/learning/knowledge/memories/proposals").contentType("application/json").content("{\"sessionId\":\"iso-session\",\"messageId\":\"iso-message\",\"userText\":\"我偏好中文回复\",\"ttlSeconds\":60}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
  var pending=mapper.readTree(proposal);
  assertIso(pending,"expiresAt");assertIso(pending,"createdAt");
  org.junit.jupiter.api.Assertions.assertEquals("PENDING",pending.get("state").asText());
  String id=pending.get("id").asText();
  var confirmed=mapper.readTree(api.perform(post("/ai/learning/knowledge/memories/"+id+"/confirm").contentType("application/json").content("{\"version\":1}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
  var recalled=mapper.readTree(api.perform(get("/ai/learning/knowledge/memories/recall")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
  assertIso(recalled.get("facts").get(0),"expiresAt");
  var deleted=mapper.readTree(api.perform(delete("/ai/learning/knowledge/memories/"+id).param("version",confirmed.get("version").asText())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
  assertIso(deleted,"deletedAt");
 }
 private static void assertIso(com.fasterxml.jackson.databind.JsonNode node,String field){org.junit.jupiter.api.Assertions.assertTrue(node.get(field).isTextual(),field+" must be an ISO Instant JSON string, never epoch seconds");org.junit.jupiter.api.Assertions.assertDoesNotThrow(()->java.time.Instant.parse(node.get(field).textValue()));}
}
