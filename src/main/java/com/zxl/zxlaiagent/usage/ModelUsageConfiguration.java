package com.zxl.zxlaiagent.usage;

import com.zxl.zxlaiagent.security.SecurityDatabase;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name="agent.security.mode", havingValue="required")
public class ModelUsageConfiguration {
    @Bean public ScopedModelBudgetFactory scopedModelBudgetFactory(SecurityDatabase database, Environment env) {
        return new ScopedModelBudgetFactory(database, new ScopedModelBudgetFactory.Policy(
                env.getProperty("agent.usage.enabled", Boolean.class, false),
                env.getProperty("agent.usage.run-token-limit", Long.class, 20000L),
                env.getProperty("agent.usage.run-call-limit", Integer.class, 8),
                env.getProperty("agent.usage.call-token-estimate", Long.class, 4096L),
                env.getProperty("agent.usage.tenant-day-token-limit", Long.class, 1000000L),
                env.getProperty("agent.usage.tenant-day-call-limit", Integer.class, 1000)),
                new BoundedModelRouter(env.getProperty("spring.ai.dashscope.chat.options.model", "qwen-plus"),
                        env.getProperty("agent.usage.fallback-model")));
    }
}
