package com.zxl.zxlaiagent.knowledge;

import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real PostgreSQL repository + actual controller, standalone HTTP contract without model context. */
class KnowledgeApiPgTest {
    MockMvc mvc;
    @BeforeEach void setup(){var db=new KnowledgeDatabase(System.getenv("LESSON12_DB_URL"),System.getenv("LESSON12_DB_USER"),System.getenv("LESSON12_DB_PASSWORD"));mvc=MockMvcBuilders.standaloneSetup(new KnowledgeController(new KnowledgeService(db,"api_"+java.util.UUID.randomUUID().toString().replace("-",""),new DemoKnowledgeEmbedding()))).build();}
    @Test void importReadSearchDeleteAndErrors()throws Exception{
        mvc.perform(post("/ai/learning/knowledge/documents").contentType(MediaType.APPLICATION_JSON).content("{\"documentId\":\"vpn\",\"sourceName\":\"vpn.md\",\"content\":\"# VPN\\nreset\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("IMPORTED"));
        mvc.perform(get("/ai/learning/knowledge/documents/vpn")).andExpect(status().isOk()).andExpect(jsonPath("$.rawText").value("# VPN\nreset")).andExpect(jsonPath("$.chunks.length()").value(1));
        mvc.perform(get("/ai/learning/knowledge/search").param("q","reset")).andExpect(status().isOk()).andExpect(jsonPath("$.hits.length()").value(1));
        mvc.perform(delete("/ai/learning/knowledge/documents/vpn")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DELETED"));
        mvc.perform(get("/ai/learning/knowledge/search").param("q","reset")).andExpect(status().isOk()).andExpect(jsonPath("$.hits.length()").value(0));
        mvc.perform(get("/ai/learning/knowledge/documents/unknown")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(get("/ai/learning/knowledge/search").param("q"," ")).andExpect(status().isBadRequest());
    }
    @Test void rejectUnsupportedAndReportStorage()throws Exception{
        mvc.perform(post("/ai/learning/knowledge/documents").contentType(MediaType.APPLICATION_JSON).content("{\"documentId\":\"vpn\",\"sourceName\":\"vpn.pdf\",\"content\":\"text\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mvc.perform(get("/ai/learning/knowledge/status")).andExpect(status().isOk()).andExpect(jsonPath("$.storage").value("PostgreSQL + pgvector")).andExpect(jsonPath("$.dimensions").value(8));
    }
}
