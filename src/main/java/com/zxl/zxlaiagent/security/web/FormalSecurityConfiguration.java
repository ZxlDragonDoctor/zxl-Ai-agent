package com.zxl.zxlaiagent.security.web;

import com.zxl.zxlaiagent.conversation.controller.FormalConversationController;
import com.zxl.zxlaiagent.security.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** Formal activation is explicit while the course migrates old demonstration entry points. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "agent.security.mode", havingValue = "required")
public class FormalSecurityConfiguration implements WebMvcConfigurer {
    private final Environment environment;

    public FormalSecurityConfiguration(Environment environment) {
        this.environment = environment;
        if (environment.acceptsProfiles(Profiles.of("lesson8-demo", "lesson9-demo")))
            throw new IllegalStateException("Formal authorization cannot enable unscoped classroom profiles");
    }

    @Bean public SecurityDatabase securityDatabase() { return SecurityDatabase.fromEnvironment(); }
    @Bean public AuthenticationService authenticationService(SecurityDatabase db) { return new AuthenticationService(db); }
    @Bean public AuthorizationService authorizationService(SecurityDatabase db) { return new AuthorizationService(db); }
    @Bean public ServiceCallAuthorizer serviceCallAuthorizer(SecurityDatabase db, AuthorizationService authorization) {
        return new ServiceCallAuthorizer(db, authorization);
    }

    @Bean
    public FilterRegistrationBean<WebSecurityFilter> browserSecurity(AuthenticationService authentication, AuthorizationService authorization) {
        Set<String> origins = Arrays.stream(environment.getProperty("agent.security.allowed-origins", "").split(","))
                .map(String::strip).filter(value -> !value.isEmpty()).collect(Collectors.toUnmodifiableSet());
        var registration = new FilterRegistrationBean<>(new WebSecurityFilter(authentication, authorization, origins));
        registration.setName("formalBrowserSecurity");
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return registration;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Add a business handler only after all of its methods use the trusted scoped service.
        registry.addInterceptor(new TrustedHandlerInterceptor(Set.of(AuthController.class,
                FormalConversationController.class, SecurityHealthController.class)));
    }
}
