package com.zxl.zxlaiagent.persistence;

import com.zxl.zxlaiagent.controller.LearningPersistenceController;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LearningPersistenceApiTest extends PersistenceFixture {
    private PersistentWorker worker;
    private MockMvc mvc;
    private static final String ROOT="/ai/learning/persistent-runs";
    @BeforeEach void api(){worker=new PersistentWorker(repository,false);mvc=MockMvcBuilders.standaloneSetup(new LearningPersistenceController(repository,worker)).build();}
    @AfterEach void closeWorker(){worker.close();}
    private String submit(String operation) throws Exception {
        var response=mvc.perform(post(ROOT).contentType(MediaType.APPLICATION_JSON).content("{\"operationId\":\""+operation+"\",\"title\":\"VPN\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING")).andReturn();
        return new ObjectMapper().readTree(response.getResponse().getContentAsString()).path("id").asText();
    }
    @Test void realRepositoryIsVisibleThroughApiAndReplayHasSameTicket() throws Exception {
        var first=submit("http-replay");assertTrue(repository.complete(repository.claim(first,"owner",60_000).orElseThrow()));
        String ticket=repository.get(first).ticket().id();
        mvc.perform(get(ROOT+"/"+first)).andExpect(status().isOk()).andExpect(jsonPath("$.run.status").value("SUCCEEDED"))
            .andExpect(jsonPath("$.ticket.id").value(ticket)).andExpect(jsonPath("$.attempts.length()").value(1));
        var replay=submit("http-replay");assertTrue(repository.complete(repository.claim(replay,"owner",60_000).orElseThrow()));
        mvc.perform(get(ROOT+"/"+replay)).andExpect(status().isOk()).andExpect(jsonPath("$.ticket.id").value(ticket));
        mvc.perform(post(ROOT).contentType(MediaType.APPLICATION_JSON).content("{\"operationId\":\"http-replay\",\"title\":\"Changed\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OPERATION_CONFLICT"));
        assertEquals(2,count("agent_run"));assertEquals(1,count("ticket"));
    }
    @Test void cancellationAndDisabledRecoveryAreExplicitHttpResults() throws Exception {
        String id=submit("http-cancel");
        mvc.perform(post(ROOT+"/"+id+"/cancel")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(get(ROOT+"/"+id)).andExpect(status().isOk()).andExpect(jsonPath("$.run.status").value("CANCELLED")).andExpect(jsonPath("$.attempts.length()").value(0));
        mvc.perform(post(ROOT+"/recover")).andExpect(status().isOk()).andExpect(jsonPath("$.queued").value(0));
    }
    @Test void invalidInputAndMissingRunReturnClientErrorsWithoutWrites() throws Exception {
        mvc.perform(get(ROOT+"/missing")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RUN_NOT_FOUND"));
        mvc.perform(post(ROOT).contentType(MediaType.APPLICATION_JSON).content("{\"operationId\":\"bad\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(post(ROOT).contentType(MediaType.APPLICATION_JSON).content("{\"operationId\":\"bad\",\"title\":\"VPN\",\"timeoutMs\":0}"))
            .andExpect(status().isBadRequest());
        assertEquals(0,count("agent_run"));assertEquals(0,count("business_operation"));
    }
}
