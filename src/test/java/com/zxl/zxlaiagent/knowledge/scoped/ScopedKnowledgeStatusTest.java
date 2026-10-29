package com.zxl.zxlaiagent.knowledge.scoped;

import com.zxl.zxlaiagent.knowledge.KnowledgeService;
import com.zxl.zxlaiagent.security.ActorRef;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ScopedKnowledgeStatusTest {
    @Test void statusExposesModelModeButRedactsDatabaseAndTenantMetadata() throws Exception {
        var scopes = mock(ScopedKnowledgeFactory.class); var knowledge = mock(KnowledgeService.class);
        var actor = new ActorRef("subject", "tenant", "session", 1);
        when(scopes.knowledge(actor)).thenReturn(knowledge);
        when(knowledge.status()).thenReturn(Map.of("storage", "PostgreSQL + pgvector", "embeddingMode", "http:model:revision", "dimensions", 1024,
                "postgresVersion", "private server version", "vectorVersion", "private extension", "tenantId", "private tenant"));
        var mvc = MockMvcBuilders.standaloneSetup(new ScopedKnowledgeController(scopes)).build();
        mvc.perform(get(WorkerRequestContract.BASE + "/status").requestAttr(WorkerAuthenticationFilter.ACTOR_ATTRIBUTE, actor))
                .andExpect(status().isOk()).andExpect(jsonPath("$.scope").value("authenticated"))
                .andExpect(jsonPath("$.embeddingMode").value("http:model:revision"))
                .andExpect(jsonPath("$.dimensions").value(1024)).andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.postgresVersion").doesNotExist()).andExpect(jsonPath("$.vectorVersion").doesNotExist())
                .andExpect(jsonPath("$.tenantId").doesNotExist());
        verify(knowledge, never()).list();
    }
    @Test void missingServiceActorCannotReadStatus() throws Exception {
        var scopes = mock(ScopedKnowledgeFactory.class);
        var mvc = MockMvcBuilders.standaloneSetup(new ScopedKnowledgeController(scopes)).build();
        mvc.perform(get(WorkerRequestContract.BASE + "/status")).andExpect(status().isUnauthorized());
        verifyNoInteractions(scopes);
    }
}
