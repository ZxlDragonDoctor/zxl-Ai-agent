package com.zxl.zxlaiagent.security.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import java.util.Set;

/** A typo must not disable the formal chain and silently leave demonstration endpoints enabled. */
@Configuration(proxyBeanMethods = false)
public class SecurityModeConfiguration {
    public SecurityModeConfiguration(Environment environment) {
        String mode = environment.getProperty("agent.security.mode", "legacy-demo");
        if (!Set.of("required", "legacy-demo").contains(mode))
            throw new IllegalStateException("Unsupported agent security mode");
    }
}
