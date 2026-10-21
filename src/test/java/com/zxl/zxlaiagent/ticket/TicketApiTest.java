package com.zxl.zxlaiagent.ticket;

import com.zxl.zxlaiagent.controller.LearningTicketController;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TicketApiTest {
    @Test void replayAndConflictAreVisibleViaHttp() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new LearningTicketController()).build();
        String body = "{\"operationId\":\"vpn-001\",\"title\":\"VPN\",\"description\":\"Cannot connect\"}";
        var first = mvc.perform(post("/ai/learning/tickets").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.attempts").value(1)).andReturn();
        mvc.perform(post("/ai/learning/tickets").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(content().json(first.getResponse().getContentAsString()));
        mvc.perform(get("/ai/learning/tickets")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(post("/ai/learning/tickets").contentType(MediaType.APPLICATION_JSON).content(body.replace("VPN", "Other")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OPERATION_CONFLICT"));
    }

    @Test void validationAndBudgetAreExplicitClientErrors() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new LearningTicketController()).build();
        mvc.perform(post("/ai/learning/tickets").contentType(MediaType.APPLICATION_JSON).content("{\"operationId\":\"empty-title\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_TICKET"));
        mvc.perform(post("/ai/learning/tickets").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operationId\":\"budget\",\"title\":\"VPN\",\"timeoutMs\":0}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_BUDGET"));
    }
}
