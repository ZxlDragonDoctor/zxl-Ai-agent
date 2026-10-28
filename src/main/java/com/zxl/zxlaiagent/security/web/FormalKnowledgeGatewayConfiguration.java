package com.zxl.zxlaiagent.security.web;

import com.zxl.zxlaiagent.knowledge.gateway.ScopedKnowledgeClient;
import com.zxl.zxlaiagent.knowledge.mcp.FormalKnowledgeMcpClient;
import com.zxl.zxlaiagent.agent.stream.DashScopeAgentStreamer;
import com.zxl.zxlaiagent.research.ScopedResearchFactory;
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
    private static String serviceToken(Environment environment) {
        return environment.getProperty("agent.security.internal-service-token", environment.getProperty("LESSON17_INTERNAL_SERVICE_TOKEN"));
    }
    @Bean public ScopedKnowledgeClient scopedKnowledgeClient(Environment environment,
            ServiceCallAuthorizer tickets, AuthorizationService authorization) {
        String token = serviceToken(environment);
        String origin = environment.getProperty("agent.security.knowledge-worker-origin", "http://127.0.0.1:18132");
        return new ScopedKnowledgeClient(URI.create(origin), token, tickets, authorization);
    }
    @Bean(destroyMethod = "close") public FormalKnowledgeMcpClient formalKnowledgeMcpClient(Environment environment,
            ServiceCallAuthorizer tickets, AuthorizationService authorization) {
        return new FormalKnowledgeMcpClient(URI.create(environment.getProperty("agent.security.mcp-origin", "http://127.0.0.1:18135")),
                serviceToken(environment), tickets, authorization);
    }
    @Bean public ScopedResearchFactory scopedResearchFactory(FormalKnowledgeMcpClient mcp, ScopedKnowledgeClient knowledge,
            DashScopeAgentStreamer streamer, AuthorizationService authorization) {
        return new ScopedResearchFactory(mcp, knowledge, streamer, authorization);
    }
}
