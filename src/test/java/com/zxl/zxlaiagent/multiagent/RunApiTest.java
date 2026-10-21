package com.zxl.zxlaiagent.multiagent;

import com.zxl.zxlaiagent.controller.MultiAgentController;
import com.zxl.zxlaiagent.multiagent.orchestration.OrchestratorAgent;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RunApiTest {
    @Test void missingRunIs404ForQueryAndCancel() {
        var orchestrator = mock(OrchestratorAgent.class);
        when(orchestrator.cancel("missing")).thenReturn(new OrchestratorAgent.CancelReceipt("not_found", null));
        var controller = new MultiAgentController(); ReflectionTestUtils.setField(controller, "orchestratorAgent", orchestrator);
        assertEquals(404, controller.getRun("missing").getStatusCode().value());
        assertEquals(404, controller.cancelRun("missing").getStatusCode().value());
    }
}
