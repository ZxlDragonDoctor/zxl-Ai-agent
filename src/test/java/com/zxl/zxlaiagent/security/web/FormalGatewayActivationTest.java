package com.zxl.zxlaiagent.security.web;

import com.zxl.zxlaiagent.controller.AiController;
import com.zxl.zxlaiagent.conversation.controller.FormalAgentChatController;
import com.zxl.zxlaiagent.conversation.service.AgentConversationService;
import com.zxl.zxlaiagent.knowledge.gateway.ScopedKnowledgeClient;
import com.zxl.zxlaiagent.security.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class FormalGatewayActivationTest {
    private final ApplicationContextRunner gateway = new ApplicationContextRunner()
            .withUserConfiguration(FormalKnowledgeGatewayConfiguration.class)
            .withBean(com.zxl.zxlaiagent.agent.stream.DashScopeAgentStreamer.class, () -> mock(com.zxl.zxlaiagent.agent.stream.DashScopeAgentStreamer.class))
            .withBean(ServiceCallAuthorizer.class, () -> mock(ServiceCallAuthorizer.class))
            .withBean(AuthorizationService.class, () -> mock(AuthorizationService.class));
    private static final String TOKEN = "agent.security.internal-service-token=abcdefghijklmnopqrstuvwxyz_0123456789-ABCDEFGHIJKLMNO";

    @Test void formalGatewayRequiresExplicitServiceCredentialAndFixedLoopbackOrigin() {
        gateway.withPropertyValues("agent.security.mode=required", TOKEN).run(ctx ->
                assertThat(ctx).hasNotFailed().hasSingleBean(ScopedKnowledgeClient.class));
        gateway.withPropertyValues("agent.security.mode=required").run(ctx -> assertThat(ctx).hasFailed());
        gateway.withPropertyValues("agent.security.mode=required", TOKEN,
                "agent.security.knowledge-worker-origin=https://example.com").run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test void legacyModeDoesNotInstantiateFormalGateway() {
        gateway.withPropertyValues("agent.security.mode=legacy-demo").run(ctx ->
                assertThat(ctx).hasNotFailed().doesNotHaveBean(ScopedKnowledgeClient.class));
    }

    @Test void formalModeInstantiatesOnlyThePreparedChatController() {
        var controllers = new ApplicationContextRunner().withUserConfiguration(AiController.class, FormalAgentChatController.class)
                .withBean(AgentConversationService.class, () -> mock(AgentConversationService.class));
        controllers.withPropertyValues("agent.security.mode=required").run(ctx -> assertThat(ctx)
                .hasNotFailed().hasSingleBean(FormalAgentChatController.class).doesNotHaveBean(AiController.class));
    }

    @Test void formalMainCannotAccidentallyScanTheIndependentWorkerProfile() {
        var environment = new MockEnvironment(); environment.setActiveProfiles("lesson17-worker");
        assertThrows(IllegalStateException.class, () -> new FormalSecurityConfiguration(environment));
    }
}
