package com.zxl.zxlaiagent.security.web;

import com.zxl.zxlaiagent.knowledge.gateway.ScopedKnowledgeClient;
import com.zxl.zxlaiagent.security.AuthorizationService;
import com.zxl.zxlaiagent.security.ServiceCallAuthorizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import java.net.URI;

/** Required-mode internal credentials are supplied by deployment; there is no demo-token fallback. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "agent.security.mode", havingValue = "required")
public class FormalKnowledgeGatewayConfiguration {
    @Bean public ScopedKnowledgeClient scopedKnowledgeClient(Environment environment,
            ServiceCallAuthorizer tickets, AuthorizationService authorization) {
        String token = environment.getProperty("agent.security.internal-service-token",
                environment.getProperty("LESSON17_INTERNAL_SERVICE_TOKEN"));
        String origin = environment.getProperty("agent.security.knowledge-worker-origin", "http://127.0.0.1:18132");
        return new ScopedKnowledgeClient(URI.create(origin), token, tickets, authorization);
    }
}
