package com.zxl.zxlaiagent.skills;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class FormalSkillConfigurationTest {
    @Test void formalConfigurationPublishesScopedRunnerOnlyWithItsTrustedDependencies() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(SkillConfiguration.class)
                .withPropertyValues("agent.security.mode=required")
                .withBean(com.zxl.zxlaiagent.research.ResearchRunner.Gateway.class, () -> mock(com.zxl.zxlaiagent.research.ResearchRunner.Gateway.class))
                .withBean(com.zxl.zxlaiagent.research.ResearchRunner.Model.class, () -> mock(com.zxl.zxlaiagent.research.ResearchRunner.Model.class))
                .withBean(com.zxl.zxlaiagent.research.ResearchRunner.Verifier.class, () -> mock(com.zxl.zxlaiagent.research.ResearchRunner.Verifier.class))
                .withBean(com.zxl.zxlaiagent.research.ScopedResearchFactory.class, () -> mock(com.zxl.zxlaiagent.research.ScopedResearchFactory.class))
                .withBean(com.zxl.zxlaiagent.security.AuthorizationService.class, () -> mock(com.zxl.zxlaiagent.security.AuthorizationService.class))
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(1, context.getBeansOfType(ScopedSkillRunner.class).size());
                });
    }
    @Test void requiredModeUsesExplicitFormalDefaultAndNeverLegacyEnvironment() {
        var environment = new MockEnvironment().withProperty("agent.security.mode", "required")
                .withProperty("LESSON16_DEFAULT_VERSION", "v1");
        var registry = new SkillConfiguration().skillRegistry(environment);
        assertEquals("v3", registry.defaultVersion("it-ticket-analysis"));
        assertThrows(IllegalArgumentException.class, () -> registry.select("it-ticket-analysis", "v1"));
    }
    @Test void invalidFormalDefaultFailsInsteadOfDowngrading() {
        for (String version : new String[]{"v1", "v2", "v99", ""}) {
            var environment = new MockEnvironment().withProperty("agent.security.mode", "required")
                    .withProperty("agent.skills.formal-default-version", version);
            assertThrows(IllegalArgumentException.class, () -> new SkillConfiguration().skillRegistry(environment));
        }
        var environment = new MockEnvironment().withProperty("agent.security.mode", "required")
                .withProperty("agent.skills.formal-default-version", "v4");
        assertEquals("v4", new SkillConfiguration().skillRegistry(environment).defaultVersion("it-ticket-analysis"));
    }
}
