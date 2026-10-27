package com.zxl.zxlaiagent.security.web;

import com.zxl.zxlaiagent.security.SecurityFailure;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import java.util.Set;

/** Route permission alone must not activate an old controller that still calls unscoped services. */
public final class TrustedHandlerInterceptor implements HandlerInterceptor {
    private final Set<Class<?>> reviewedHandlers;

    public TrustedHandlerInterceptor(Set<Class<?>> reviewedHandlers) {
        this.reviewedHandlers = Set.copyOf(reviewedHandlers);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (handler instanceof HandlerMethod method && reviewedHandlers.contains(method.getBeanType())) return true;
        throw new SecurityFailure("AUTHORIZATION_INTEGRATION_PENDING", 503);
    }
}
