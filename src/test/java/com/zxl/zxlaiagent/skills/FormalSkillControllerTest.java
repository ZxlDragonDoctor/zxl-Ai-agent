package com.zxl.zxlaiagent.skills;

import com.zxl.zxlaiagent.security.*;
import com.zxl.zxlaiagent.security.web.WebSecurityAdvice;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.function.Supplier;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FormalSkillControllerTest {
    private static final AuthenticatedActor ACTOR = new AuthenticatedActor("subject", "tenant", "session", 1, "employee", 1);
    @Test void anonymousCannotEnumerateFormalDefinitions() throws Exception {
        var registry = mock(SkillRegistry.class); var authorization = mock(AuthorizationService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new FormalSkillController(registry, authorization))
                .setControllerAdvice(new WebSecurityAdvice()).build();
        mvc.perform(get("/ai/learning/skills")).andExpect(status().isUnauthorized());
        verifyNoInteractions(registry, authorization);
    }
    @Test void directoryAndVersionArePublishedOnlyUnderCurrentActorGate() throws Exception {
        var registry = new SkillRegistry(SkillCatalogue.formalBuiltIns());
        var authorization = mock(AuthorizationService.class);
        when(authorization.gateAll(eq(ACTOR.ref()), anyList(), eq(Phase.RESULT_PUBLISH), isNull(), any()))
                .thenAnswer(call -> ((Supplier<?>) call.getArgument(4)).get());
        var mvc = MockMvcBuilders.standaloneSetup(new FormalSkillController(registry, authorization)).build();
        mvc.perform(get("/ai/learning/skills").requestAttr("com.zxl.zxlaiagent.security.web.AuthenticatedRequest.actor", ACTOR))
                .andExpect(status().isOk()).andExpect(jsonPath("$.defaultVersion").value("v3"))
                .andExpect(jsonPath("$.versions.length()").value(2)).andExpect(jsonPath("$.administrationEnabled").value(false));
        mvc.perform(get("/ai/learning/skills/it-ticket-analysis/v1").requestAttr("com.zxl.zxlaiagent.security.web.AuthenticatedRequest.actor", ACTOR))
                .andExpect(status().isNotFound());
    }
}
