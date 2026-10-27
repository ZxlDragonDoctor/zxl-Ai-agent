package com.zxl.zxlaiagent.security.web;

import com.zxl.zxlaiagent.security.SecurityFailure;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.io.IOException;

/** MVC resolves controller exceptions before they return through the servlet filter. */
@RestControllerAdvice
@ConditionalOnProperty(name = "agent.security.mode", havingValue = "required")
public final class WebSecurityAdvice {
    @ExceptionHandler(SecurityFailure.class)
    public void handle(SecurityFailure failure, HttpServletResponse response) throws IOException {
        WebSecurityErrors.write(response, failure.status(), failure.code());
    }
}
