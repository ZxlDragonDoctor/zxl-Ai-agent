package com.zxl.zxlaiagent.knowledge;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import java.sql.SQLException;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;

class KnowledgeFailureApiTest {
    @Test void unavailableDatabaseIs503ForReadsWritesAndStatusWithoutClaimingAvailability()throws Exception{
        var service=mock(KnowledgeService.class);
        var unavailable=new IllegalStateException("PostgreSQL operation failed",new SQLException("connection refused","08001"));
        when(service.status()).thenThrow(unavailable);when(service.list()).thenThrow(unavailable);
        when(service.detail(anyString())).thenThrow(unavailable);when(service.delete(anyString())).thenThrow(unavailable);
        when(service.importDocument(anyString(),anyString(),anyString())).thenThrow(unavailable);
        when(service.search(anyString(),anyInt())).thenThrow(unavailable);
        var mvc=MockMvcBuilders.standaloneSetup(new KnowledgeController(service)).build();
        var requests=java.util.List.of(get("/ai/learning/knowledge/status"),get("/ai/learning/knowledge/documents"),get("/ai/learning/knowledge/documents/vpn"),delete("/ai/learning/knowledge/documents/vpn"),get("/ai/learning/knowledge/search").param("q","VPN"),post("/ai/learning/knowledge/documents").contentType(MediaType.APPLICATION_JSON).content("{\"documentId\":\"vpn\",\"sourceName\":\"vpn.md\",\"content\":\"text\"}"));
        for(var request:requests)mvc.perform(request).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("STORAGE_UNAVAILABLE")).andExpect(jsonPath("$.message").value(not(containsString("保持可用"))));
    }
    @Test void genericOperationFailureDoesNotAssertOldRevisionIsAvailableOrLeakDetails()throws Exception{
        var service=mock(KnowledgeService.class);when(service.status()).thenThrow(new IllegalStateException("private provider failure details"));
        MockMvcBuilders.standaloneSetup(new KnowledgeController(service)).build().perform(get("/ai/learning/knowledge/status"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("OPERATION_FAILED"))
                .andExpect(jsonPath("$.message").value(not(containsString("保持可用"))))
                .andExpect(jsonPath("$.message").value(not(containsString("private provider"))));
    }
}
