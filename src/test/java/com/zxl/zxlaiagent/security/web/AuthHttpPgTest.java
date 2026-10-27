package com.zxl.zxlaiagent.security.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zxl.zxlaiagent.security.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real PG plus MVC/filter serialization; this is not a browser or live-port acceptance test. */
class AuthHttpPgTest {
    private final ObjectMapper json = new ObjectMapper();
    private SecurityDatabase db;
    private MockMvc mvc;
    private String subject, tenant;

    @BeforeEach void setup() {
        db = SecurityDatabase.fromEnvironment();
        var authentication = new AuthenticationService(db);
        subject = "http-test-" + UUID.randomUUID(); tenant = subject + "-tenant";
        authentication.provision(subject, subject, "synthetic-password", tenant, "employee");
        mvc = MockMvcBuilders.standaloneSetup(new AuthController(authentication))
                .setControllerAdvice(new WebSecurityAdvice())
                .addFilters(new WebSecurityFilter(authentication, new AuthorizationService(db), Set.of("http://127.0.0.1:5174")))
                .build();
    }

    @AfterEach void cleanup() {
        if (db == null || subject == null) return;
        db.tx(connection -> {
            try (var statement = SecurityDatabase.statement(connection, "DELETE FROM lesson17.authorization_audit WHERE subject_id=?", subject)) { statement.executeUpdate(); }
            try (var statement = SecurityDatabase.statement(connection, "DELETE FROM lesson17.subject WHERE subject_id=?", subject)) { statement.executeUpdate(); }
            try (var statement = SecurityDatabase.statement(connection, "DELETE FROM lesson17.tenant WHERE tenant_id=?", tenant)) { statement.executeUpdate(); }
            return null;
        });
    }

    private Cookie cookie(MvcResult result, String name) {
        String header = result.getResponse().getHeaders("Set-Cookie").stream()
                .filter(value -> value.startsWith(name + "=")).reduce((a, b) -> b).orElseThrow();
        return new Cookie(name, header.substring(name.length() + 1, header.indexOf(';')));
    }

    private MvcResult bootstrap() throws Exception {
        return mvc.perform(get("/api/auth/status").contextPath("/api"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("required"))
                .andExpect(jsonPath("$.authenticated").value(false)).andReturn();
    }

    private String loginBody(String candidateTenant) throws Exception {
        return json.writeValueAsString(java.util.Map.of("loginName", subject, "password", "synthetic-password", "tenantId", candidateTenant));
    }

    @Test void loginStatusLogoutAndRevokedCookieUseRealAuthority() throws Exception {
        var bootstrap = bootstrap(); var anonymousCsrf = cookie(bootstrap, AuthCookies.CSRF);
        var loggedIn = mvc.perform(post("/api/auth/login").contextPath("/api").cookie(anonymousCsrf)
                        .header("X-CSRF-Token", anonymousCsrf.getValue()).contentType(MediaType.APPLICATION_JSON).content(loginBody(tenant)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.principal.subjectId").value(subject)).andExpect(jsonPath("$.principal.sessionId").doesNotExist()).andReturn();
        var session = cookie(loggedIn, AuthCookies.SESSION); var csrf = cookie(loggedIn, AuthCookies.CSRF);
        assertNotEquals(anonymousCsrf.getValue(), csrf.getValue());
        assertFalse(loggedIn.getResponse().getContentAsString().contains(session.getValue()));
        mvc.perform(get("/api/auth/status").contextPath("/api").cookie(session, csrf))
                .andExpect(status().isOk()).andExpect(jsonPath("$.authenticated").value(true));
        mvc.perform(post("/api/auth/logout").contextPath("/api").cookie(session, csrf).header("X-CSRF-Token", "forged"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        mvc.perform(post("/api/auth/logout").contextPath("/api").cookie(session, csrf).header("X-CSRF-Token", csrf.getValue()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/conversations").contextPath("/api").cookie(session, csrf))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/auth/status").contextPath("/api").cookie(session, csrf))
                .andExpect(status().isOk()).andExpect(jsonPath("$.authenticated").value(false));
    }

    @Test void candidateTenantCannotGrantMembershipAndFailureCanBeRetried() throws Exception {
        var csrf = cookie(bootstrap(), AuthCookies.CSRF);
        mvc.perform(post("/api/auth/login").contextPath("/api").cookie(csrf).header("X-CSRF-Token", csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON).content(loginBody("foreign-tenant")))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        var fresh = cookie(bootstrap(), AuthCookies.CSRF);
        mvc.perform(post("/api/auth/login").contextPath("/api").cookie(fresh).header("X-CSRF-Token", fresh.getValue())
                        .contentType(MediaType.APPLICATION_JSON).content(loginBody(tenant)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.principal.tenantId").value(tenant));
    }

    @Test void malformedAndTrailingJsonAreSafeControllerErrors() throws Exception {
        var csrf = cookie(bootstrap(), AuthCookies.CSRF);
        for (String body : new String[]{"{", loginBody(tenant) + " {}", "{\"password\":\"private-marker\"}"}) {
            var result = mvc.perform(post("/api/auth/login").contextPath("/api").cookie(csrf).header("X-CSRF-Token", csrf.getValue())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_LOGIN_REQUEST")).andReturn();
            assertFalse(result.getResponse().getContentAsString().contains("private-marker"));
            assertFalse(result.getResponse().getContentAsString().contains("Exception"));
        }
    }

    @Test void crossSiteAndUnclassifiedExecutionPathsStayClosed() throws Exception {
        var csrf = cookie(bootstrap(), AuthCookies.CSRF);
        mvc.perform(post("/api/auth/login").contextPath("/api").cookie(csrf).header("X-CSRF-Token", csrf.getValue())
                        .header("Origin", "http://evil.example").contentType(MediaType.APPLICATION_JSON).content(loginBody(tenant)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ORIGIN_DENIED"));
        mvc.perform(get("/api/ai/agent/chat").contextPath("/api").param("message", "do not run"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/unclassified-controller").contextPath("/api"))
                .andExpect(status().isNotFound());
    }
}
