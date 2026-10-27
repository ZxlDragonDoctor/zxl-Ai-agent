package com.zxl.zxlaiagent.security.web;

import com.zxl.zxlaiagent.security.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthControllerTest {
    private final AuthenticationService auth = mock(AuthenticationService.class);
    private final AuthController controller = new AuthController(auth);
    private final AuthenticatedActor actor = new AuthenticatedActor("alice", "tenant-a", "session-a", 1, "employee", 1);
    private final String csrf = "a".repeat(43);

    private MockHttpServletRequest request() {
        var request = new MockHttpServletRequest("GET", "/api/auth/status");
        request.setContextPath("/api");
        return request;
    }

    private MockHttpServletRequest loginRequest(String body) {
        var request = request(); request.setMethod("POST"); request.setRequestURI("/api/auth/login");
        request.setCookies(new Cookie(AuthCookies.CSRF, csrf));
        request.addHeader("X-CSRF-Token", csrf);
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    @Test void anonymousBootstrapHasHttpOnlyCsrfWithoutCreatingSession() {
        var response = new MockHttpServletResponse();
        var status = controller.status(request(), response);
        assertEquals("required", status.mode()); assertFalse(status.authenticated()); assertNull(status.principal());
        assertTrue(AuthCookies.validToken(status.csrfToken()));
        var cookie = response.getHeader("Set-Cookie");
        assertTrue(cookie.contains("HttpOnly")); assertTrue(cookie.contains("Path=/api"));
        assertTrue(cookie.contains("SameSite=Lax")); assertFalse(cookie.contains(AuthCookies.SESSION));
        verifyNoInteractions(auth);
    }

    @Test void validStatusDoesNotRotateCsrfOrExposeSessionIdentity() {
        var request = request(); request.setCookies(new Cookie(AuthCookies.SESSION, "credential"), new Cookie(AuthCookies.CSRF, csrf));
        when(auth.authenticate("credential")).thenReturn(actor); when(auth.loginName(actor.ref())).thenReturn("alice-login");
        var status = controller.status(request, new MockHttpServletResponse());
        assertTrue(status.authenticated()); assertEquals("alice-login", status.principal().loginName());
        assertEquals(csrf, status.csrfToken()); assertFalse(status.toString().contains(csrf));
        verify(auth).validateCsrf(actor.ref(), csrf); verify(auth, never()).rotateCsrf(actor.ref());
        assertEquals(4, status.principal().getClass().getRecordComponents().length);
    }

    @Test void missingAuthenticatedCsrfIsReissuedInDatabase() {
        var request = request(); request.setCookies(new Cookie(AuthCookies.SESSION, "credential"));
        when(auth.authenticate("credential")).thenReturn(actor); when(auth.rotateCsrf(actor.ref())).thenReturn(csrf);
        when(auth.loginName(actor.ref())).thenReturn("alice-login");
        var response = new MockHttpServletResponse();
        assertEquals(csrf, controller.status(request, response).csrfToken());
        assertTrue(response.getHeader("Set-Cookie").contains(csrf)); verify(auth).rotateCsrf(actor.ref());
    }

    @Test void expiredSessionBecomesAnonymousButDatabaseFailureDoesNot() {
        var request = request(); request.setCookies(new Cookie(AuthCookies.SESSION, "expired"));
        when(auth.authenticate("expired")).thenThrow(new SecurityFailure("UNAUTHENTICATED", 401));
        var response = new MockHttpServletResponse(); assertFalse(controller.status(request, response).authenticated());
        assertTrue(response.getHeaders("Set-Cookie").stream().anyMatch(v -> v.startsWith("ZXL_SESSION=") && v.contains("Max-Age=0")));
        doThrow(new SecurityFailure("AUTHORITY_UNAVAILABLE", 503)).when(auth).authenticate("expired");
        assertEquals(503, assertThrows(SecurityFailure.class, () -> controller.status(request, new MockHttpServletResponse())).status());
    }

    @Test void loginReturnsOnlyPublicIdentityAndSetsSecureCredentialCookies() {
        var request = loginRequest("{\"loginName\":\"alice-login\",\"password\":\"synthetic\",\"tenantId\":\"tenant-a\"}"); request.setSecure(true);
        when(auth.login("alice-login", "synthetic", "tenant-a"))
                .thenReturn(new AuthenticationService.LoginSession(actor, "opaque-private", csrf, Instant.now().plusSeconds(300)));
        when(auth.loginName(actor.ref())).thenReturn("alice-login");
        var response = new MockHttpServletResponse(); var status = controller.login(request, response);
        assertTrue(status.authenticated()); assertFalse(status.toString().contains("opaque-private"));
        assertEquals(2, response.getHeaders("Set-Cookie").size());
        for (var cookie : response.getHeaders("Set-Cookie")) { assertTrue(cookie.contains("Secure")); assertTrue(cookie.contains("HttpOnly")); }
    }

    @Test void loginRequiresCsrfBeforePasswordCheck() {
        var request = loginRequest("{}"); request.removeHeader("X-CSRF-Token");
        assertEquals(403, assertThrows(SecurityFailure.class, () -> controller.login(request, new MockHttpServletResponse())).status());
        verifyNoInteractions(auth);
    }

    @Test void authenticatedIdentityMustLogoutBeforeSwitchingAccounts() {
        var request = loginRequest("{}"); request.setCookies(new Cookie(AuthCookies.CSRF, csrf), new Cookie(AuthCookies.SESSION, "existing"));
        when(auth.authenticate("existing")).thenReturn(actor);
        assertEquals("LOGOUT_REQUIRED", assertThrows(SecurityFailure.class, () -> controller.login(request, new MockHttpServletResponse())).code());
        verify(auth, never()).login(any(), any(), any());
    }

    @Test void loginRejectsUnknownDuplicateNonStringAndOversizedFieldsBeforeAuthentication() {
        for (String body : new String[]{"{}", "null", "[]", "{\"loginName\":\"a\",\"password\":\"p\",\"tenantId\":\"t\",\"subjectId\":\"forged\"}",
                "{\"loginName\":\"a\",\"loginName\":\"b\",\"password\":\"p\",\"tenantId\":\"t\"}",
                "{\"loginName\":7,\"password\":\"p\",\"tenantId\":\"t\"}", " ".repeat(4097)}) {
            assertEquals("INVALID_LOGIN_REQUEST", assertThrows(SecurityFailure.class,
                    () -> controller.login(loginRequest(body), new MockHttpServletResponse())).code());
        }
        verifyNoInteractions(auth);
    }

    @Test void loginRejectsSecondJsonValueRatherThanIgnoringIt() {
        var request = loginRequest("{\"loginName\":\"a\",\"password\":\"p\",\"tenantId\":\"t\"} {}");
        assertEquals("INVALID_LOGIN_REQUEST", assertThrows(SecurityFailure.class,
                () -> controller.login(request, new MockHttpServletResponse())).code());
        verifyNoInteractions(auth);
    }

    @Test void logoutRevokesBeforeClearingCookiesAndCannotUseClientActor() {
        var request = request(); request.setAttribute(AuthenticatedRequest.ACTOR, actor); request.addHeader("X-CSRF-Token", csrf);
        var response = new MockHttpServletResponse(); controller.logout(request, response);
        var order = inOrder(auth); order.verify(auth).validateCsrf(actor.ref(), csrf); order.verify(auth).revoke(actor.ref());
        assertEquals(204, response.getStatus()); assertEquals(2, response.getHeaders("Set-Cookie").size());
        assertThrows(SecurityFailure.class, () -> controller.logout(request(), new MockHttpServletResponse()));
    }
}
