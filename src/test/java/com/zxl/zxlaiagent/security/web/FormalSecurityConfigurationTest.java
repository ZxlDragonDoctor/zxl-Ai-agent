package com.zxl.zxlaiagent.security.web;

import com.zxl.zxlaiagent.config.CorsConfig;
import com.zxl.zxlaiagent.conversation.controller.FormalConversationController;
import com.zxl.zxlaiagent.conversation.service.ConversationService;
import com.zxl.zxlaiagent.security.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class FormalSecurityConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(SecurityModeConfiguration.class, FormalSecurityConfiguration.class,
                    AuthController.class, SecurityHealthController.class, FormalConversationController.class, CorsConfig.class)
            .withBean(ConversationService.class, () -> mock(ConversationService.class));

    @Test void requiredModeActivatesPgAuthorityAndExcludesLegacyCors() {
        context.withPropertyValues("agent.security.mode=required", "agent.security.allowed-origins=http://127.0.0.1:5174")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed().hasSingleBean(SecurityDatabase.class)
                            .hasSingleBean(AuthenticationService.class).hasSingleBean(AuthorizationService.class)
                            .hasSingleBean(AuthController.class).hasSingleBean(FormalConversationController.class)
                            .hasSingleBean(SecurityHealthController.class).doesNotHaveBean(CorsConfig.class);
                    assertThat(ctx.getBean("browserSecurity")).isInstanceOf(org.springframework.boot.web.servlet.FilterRegistrationBean.class);
                });
    }

    @Test void explicitLegacyModeDoesNotCreateFormalSecurityBeans() {
        context.withPropertyValues("agent.security.mode=legacy-demo").run(ctx ->
                assertThat(ctx).hasNotFailed().doesNotHaveBean(SecurityDatabase.class)
                        .doesNotHaveBean(AuthController.class).doesNotHaveBean(FormalConversationController.class)
                        .hasSingleBean(CorsConfig.class));
    }

    @Test void misspelledModeFailsStartupRatherThanExposingOldEndpoints() {
        context.withPropertyValues("agent.security.mode=requried").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class);
        });
    }

    @Test void formalModeRejectsUnscopedClassroomProfiles() {
        for (String profile : new String[]{"lesson8-demo", "lesson9-demo"}) context.withPropertyValues("agent.security.mode=required")
                .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles(profile))
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class);
                });
    }

    @Test void wildcardCredentialOriginsCannotBeEnabledInFormalMode() {
        context.withPropertyValues("agent.security.mode=required", "agent.security.allowed-origins=*").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).hasRootCauseInstanceOf(SecurityFailure.class);
        });
    }

    @Test void effectiveDefaultClassroomProfilesAlsoFailStartup() {
        for (String profile : new String[]{"lesson8-demo", "lesson9-demo"}) context.withPropertyValues("agent.security.mode=required")
                .withInitializer(ctx -> ctx.getEnvironment().setDefaultProfiles(profile))
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class);
                });
    }

    @Test void inactiveDefaultProfileDoesNotBlockAnOrdinaryActiveProfile() {
        context.withPropertyValues("agent.security.mode=required")
                .withInitializer(ctx -> {
                    ctx.getEnvironment().setDefaultProfiles("lesson9-demo");
                    ctx.getEnvironment().setActiveProfiles("ordinary");
                }).run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(AuthenticationService.class));
    }
}
