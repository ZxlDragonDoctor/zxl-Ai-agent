package com.zxl.zxlaiagent.multiagent.observer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.management.ObjectName;
import javax.management.StandardMBean;
import java.lang.management.ManagementFactory;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "agent.observability.jmx-enabled", havingValue = "true")
class AgentMetricExportConfiguration {
    @Bean(destroyMethod = "close")
    AutoCloseable agentMetricExport(ObjectMapper mapper) throws Exception {
        var metrics = new AgentMetricExport();
        var server = ManagementFactory.getPlatformMBeanServer();
        var name = new ObjectName("com.zxl.agent:type=ModelMetrics");
        try {
            ModelMetrics view = () -> mapper.writeValueAsString(metrics.snapshot());
            server.registerMBean(new StandardMBean(view, ModelMetrics.class), name);
        } catch (Exception failure) {
            metrics.close();
            throw failure;
        }
        return () -> {
            try { server.unregisterMBean(name); }
            finally { metrics.close(); }
        };
    }

    public interface ModelMetrics {
        String getSnapshot() throws com.fasterxml.jackson.core.JsonProcessingException;
    }
}
