package com.zxl.zxlaiagent.security.web;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.zxl.zxlaiagent.security.AuthenticatedActor;
import com.zxl.zxlaiagent.security.AuthenticationService;
import com.zxl.zxlaiagent.security.SecurityFailure;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.Semaphore;

/** Cookie credentials stay outside JSON and model context. Provisioning is an offline operation. */
@RestController
@RequestMapping("/auth")
@ConditionalOnProperty(name = "agent.security.mode", havingValue = "required")
public final class AuthController {
    private static final Set<String> LOGIN_FIELDS = Set.of("loginName", "password", "tenantId");
    private final AuthenticationService authentication;
    private final ObjectMapper json = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final Semaphore loginSlots = new Semaphore(4);

    public AuthController(AuthenticationService authentication) {
        this.authentication = authentication;
    }

    public record PublicPrincipal(String subjectId, String tenantId, String loginName, String role) {}

    public record Status(String mode, boolean authenticated, PublicPrincipal principal, String csrfToken) {
        @Override public String toString() { return "AuthStatus[authenticated=" + authenticated + "]"; }
    }

    @GetMapping("/status")
    public Status status(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        String credential;
        try {
            credential = AuthCookies.read(request, AuthCookies.SESSION);
        } catch (SecurityFailure malformedCookies) {
            AuthCookies.clear(request, response);
            return anonymous(request, response, true);
        }
        if (credential == null || credential.isBlank()) return anonymous(request, response, false);
        try {
            var actor = authentication.authenticate(credential);
            String csrf = AuthCookies.read(request, AuthCookies.CSRF);
            try {
                if (!AuthCookies.validToken(csrf)) throw new SecurityFailure("CSRF_INVALID", 403);
                authentication.validateCsrf(actor.ref(), csrf);
            } catch (SecurityFailure invalidCsrf) {
                if (invalidCsrf.status() != 403) throw invalidCsrf;
                csrf = authentication.rotateCsrf(actor.ref());
                AuthCookies.set(request, response, AuthCookies.CSRF, csrf, Duration.ofHours(8));
            }
            return authenticated(actor, csrf);
        } catch (SecurityFailure failure) {
            if (failure.status() != 401) throw failure;
            AuthCookies.clear(request, response);
            return anonymous(request, response, true);
        }
    }

    @PostMapping("/login")
    public Status login(HttpServletRequest request, HttpServletResponse response) {
        // Defense in depth: this endpoint is also protected by WebSecurityFilter.
        AuthCookies.requireDoubleSubmit(request);
        requireLoggedOut(request);
        if (!loginSlots.tryAcquire()) throw new SecurityFailure("AUTH_CAPACITY", 429);
        try {
            JsonNode body = readLogin(request);
            var session = authentication.login(body.get("loginName").textValue(),
                    body.get("password").textValue(), body.get("tenantId").textValue());
            var result = authenticated(session.actor(), session.csrfToken());
            Duration lifetime = Duration.between(Instant.now(), session.expiresAt());
            if (lifetime.isNegative() || lifetime.isZero()) throw new SecurityFailure("UNAUTHENTICATED", 401);
            AuthCookies.set(request, response, AuthCookies.SESSION, session.credential(), lifetime);
            AuthCookies.set(request, response, AuthCookies.CSRF, session.csrfToken(), lifetime);
            response.setHeader("Cache-Control", "no-store");
            return result;
        } finally {
            loginSlots.release();
        }
    }

    @PostMapping("/logout")
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        var actor = AuthenticatedRequest.require(request);
        authentication.validateCsrf(actor.ref(), request.getHeader("X-CSRF-Token"));
        authentication.revoke(actor.ref());
        AuthCookies.clear(request, response);
        response.setHeader("Cache-Control", "no-store");
        response.setStatus(204);
    }

    private Status anonymous(HttpServletRequest request, HttpServletResponse response, boolean renew) {
        String csrf = renew ? null : AuthCookies.read(request, AuthCookies.CSRF);
        if (!AuthCookies.validToken(csrf)) csrf = AuthCookies.random();
        AuthCookies.set(request, response, AuthCookies.CSRF, csrf, Duration.ofMinutes(15));
        return new Status("required", false, null, csrf);
    }

    private Status authenticated(AuthenticatedActor actor, String csrf) {
        return new Status("required", true, new PublicPrincipal(actor.subjectId(), actor.tenantId(),
                authentication.loginName(actor.ref()), actor.role()), csrf);
    }

    private void requireLoggedOut(HttpServletRequest request) {
        String credential = AuthCookies.read(request, AuthCookies.SESSION);
        if (credential == null || credential.isBlank()) return;
        try {
            authentication.authenticate(credential);
        } catch (SecurityFailure failure) {
            if (failure.status() == 401) return;
            throw failure;
        }
        throw new SecurityFailure("LOGOUT_REQUIRED", 409);
    }

    private JsonNode readLogin(HttpServletRequest request) {
        try {
            byte[] bytes = request.getInputStream().readNBytes(4097);
            if (bytes.length > 4096) throw new SecurityFailure("INVALID_LOGIN_REQUEST", 400);
            JsonNode body = json.readTree(bytes);
            if (body == null || !body.isObject() || body.size() != LOGIN_FIELDS.size())
                throw new SecurityFailure("INVALID_LOGIN_REQUEST", 400);
            var fields = body.fieldNames();
            while (fields.hasNext()) if (!LOGIN_FIELDS.contains(fields.next()))
                throw new SecurityFailure("INVALID_LOGIN_REQUEST", 400);
            for (String field : LOGIN_FIELDS) if (!body.hasNonNull(field) || !body.get(field).isTextual())
                throw new SecurityFailure("INVALID_LOGIN_REQUEST", 400);
            return body;
        } catch (IOException invalidJson) {
            throw new SecurityFailure("INVALID_LOGIN_REQUEST", 400);
        }
    }
}
