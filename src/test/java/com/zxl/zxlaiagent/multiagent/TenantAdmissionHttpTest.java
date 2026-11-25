package com.zxl.zxlaiagent.multiagent;

import com.zxl.zxlaiagent.multiagent.model.WorkflowException;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TenantAdmissionHttpTest {
    @Test void trustedTenantCapacityHasExplicit429Code() throws Exception {
        var fixture=new FormalMultiAgentControllerTest();
        when(fixture.service.submit(eq(fixture.actor.ref()),eq("synthetic"),anyMap(),isNull()))
            .thenThrow(new WorkflowException("AGENT_TENANT_CAPACITY_EXCEEDED","runs.tenant","租户执行容量已满"));
        fixture.mvc().perform(post("/api/ai/multiagent/runs").contextPath("/api").cookie(fixture.cookie())
            .header("X-CSRF-Token","synthetic-csrf").contentType("application/json").content("{\"goal\":\"synthetic\"}"))
            .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("AGENT_TENANT_CAPACITY_EXCEEDED"));
    }
}
