package com.zxl.zxlaiagent.security.web;

import com.zxl.zxlaiagent.security.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.HandlerMethod;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TrustedHandlerInterceptorTest {
    static class Reviewed { public void endpoint() {} }
    static class Legacy { public void endpoint() {} }

    @Test void onlyExactReviewedControllerTypesAreActivated() throws Exception {
        var guard = new TrustedHandlerInterceptor(Set.of(Reviewed.class));
        assertTrue(guard.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(),
                new HandlerMethod(new Reviewed(), Reviewed.class.getMethod("endpoint"))));
        var failure = assertThrows(SecurityFailure.class, () -> guard.preHandle(new MockHttpServletRequest(),
                new MockHttpServletResponse(), new HandlerMethod(new Legacy(), Legacy.class.getMethod("endpoint"))));
        assertEquals(503, failure.status()); assertEquals("AUTHORIZATION_INTEGRATION_PENDING", failure.code());
    }

    @Test void mutableInputCannotActivateAnotherHandlerLater() throws Exception {
        Set<Class<?>> input = new HashSet<>(); input.add(Reviewed.class);
        var guard = new TrustedHandlerInterceptor(input); input.add(Legacy.class);
        assertThrows(SecurityFailure.class, () -> guard.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(),
                new HandlerMethod(new Legacy(), Legacy.class.getMethod("endpoint"))));
    }

    @Test void nonMethodHandlersAreNotBusinessEntrypoints() {
        var guard = new TrustedHandlerInterceptor(Set.of());
        assertThrows(SecurityFailure.class, () -> guard.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), new Object()));
    }

    @RestController static class OldConversationController {
        final AtomicInteger calls = new AtomicInteger();
        @GetMapping("/conversations") public String list() { calls.incrementAndGet(); return "private-unscoped-history"; }
    }

    @Test void reviewedPathDoesNotExposeUnmigratedControllerBody() throws Exception {
        var oldController = new OldConversationController();
        var mvc = MockMvcBuilders.standaloneSetup(oldController).setControllerAdvice(new WebSecurityAdvice())
                .addInterceptors(new TrustedHandlerInterceptor(Set.of(AuthController.class))).build();
        var result = mvc.perform(get("/conversations")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUTHORIZATION_INTEGRATION_PENDING")).andReturn();
        assertEquals(0, oldController.calls.get());
        assertFalse(result.getResponse().getContentAsString().contains("private-unscoped-history"));
    }
}
