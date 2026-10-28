package com.zxl.zxlaiagent.agent.stream;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

/** Register on the formal MVC configuration. Spring 6.2.7 initializes SseEmitter's handler
 * before DispatcherServlet invokes afterConcurrentHandlingStarted. No reflection is used. */
public final class PreparedAgentStreamInterceptor implements AsyncHandlerInterceptor {
    private static final String ATTRIBUTE = PreparedAgentStreamInterceptor.class.getName() + ".stream";

    public static PreparedAgentStream attach(HttpServletRequest request, PreparedAgentStream stream) {
        if (request.getAttribute(ATTRIBUTE) != null) {
            stream.abandon();
            throw new IllegalStateException("请求已经绑定输出流");
        }
        request.setAttribute(ATTRIBUTE, stream);
        return stream;
    }

    @Override public void afterConcurrentHandlingStarted(HttpServletRequest request, HttpServletResponse response,
                                                         Object handler) {
        if (request.getAttribute(ATTRIBUTE) instanceof PreparedAgentStream stream) stream.activate();
    }

    @Override public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                          Object handler, Exception error) {
        if (request.getAttribute(ATTRIBUTE) instanceof PreparedAgentStream stream) stream.abandon();
    }
}
