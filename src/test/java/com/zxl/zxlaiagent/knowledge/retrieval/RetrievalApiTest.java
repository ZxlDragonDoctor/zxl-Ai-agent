package com.zxl.zxlaiagent.knowledge.retrieval;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RetrievalApiTest {
    @Test void invalidOrPrivilegedOptionsAreRejectedBeforeRetrieval() throws Exception {
        var service=mock(RetrievalService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new RetrievalController(service)).build();
        for(String input:List.of("{\"query\":\"809\",\"tenant\":\"other\"}","{\"query\":\"809\",\"topK\":\"5\"}","{\"query\":\"809\",\"rerank\":1}","{\"query\":\"\"}","[]")) {
            mvc.perform(post("/ai/learning/knowledge/retrieval").contentType(MediaType.APPLICATION_JSON).content(input))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_RETRIEVAL_OPTIONS"));
        }
        verifyNoInteractions(service);
    }
    @Test void successfulEmptyResultAndAllChannelFailureHaveDifferentContracts() throws Exception {
        var service=mock(RetrievalService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new RetrievalController(service)).build();
        when(service.retrieve(any())).thenReturn(new RetrievalService.Response("trace","809",null,"keyword","demo","disabled",1,List.of(),List.of(),"NO_CURRENT_MATCHES",List.of()));
        mvc.perform(post("/ai/learning/knowledge/retrieval").contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"809\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hits").isEmpty()).andExpect(jsonPath("$.shortageReason").value("NO_CURRENT_MATCHES"));
        when(service.retrieve(any())).thenThrow(new RetrievalService.Failure("ALL_RETRIEVAL_CHANNELS_FAILED"));
        mvc.perform(post("/ai/learning/knowledge/retrieval").contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"809\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("ALL_RETRIEVAL_CHANNELS_FAILED"));
    }
}
