package com.zxl.zxlaiagent.security.web;

import com.zxl.zxlaiagent.security.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WebSecurityFilterTest {
    final AuthenticationService auth=mock(AuthenticationService.class);
    final AuthorizationService authorization=mock(AuthorizationService.class);
    final AuthenticatedActor actor=new AuthenticatedActor("alice","tenant-a","session-a",1,"employee",1);
    final WebSecurityFilter filter=new WebSecurityFilter(auth,authorization,Set.of("http://127.0.0.1:5174"));
    MockHttpServletRequest request(String method,String path,boolean authenticated) {
        var request=new MockHttpServletRequest(method,"/api"+path);request.setContextPath("/api");
        if(authenticated)request.setCookies(new Cookie("ZXL_SESSION","valid-credential"));
        return request;
    }
    void permit() {
        when(auth.authenticate("valid-credential")).thenReturn(actor);
        when(authorization.decide(any(),anyString(),any(),any(),isNull())).thenReturn(new AuthorizationDecision("decision","epoch:1",true,"ALLOWED"));
    }
    @Test void anonymousBusinessRequestStopsBeforeController()throws Exception {
        var response=new MockHttpServletResponse();var calls=new AtomicInteger();
        filter.doFilter(request("GET","/conversations",false),response,(q,s)->calls.incrementAndGet());
        assertEquals(401,response.getStatus());assertEquals(0,calls.get());assertFalse(response.getContentAsString().contains("credential"));
    }
    @Test void acceptedActorIsAttachedExplicitlyAndContextPathCannotBypassPolicy()throws Exception {
        permit();var request=request("POST","/ai/agent/chat",true);request.addHeader("X-CSRF-Token","bound-csrf");
        var response=new MockHttpServletResponse();var calls=new AtomicInteger();
        filter.doFilter(request,response,(q,s)->{assertEquals(actor,AuthenticatedRequest.require(request));calls.incrementAndGet();});
        assertEquals(1,calls.get());verify(auth).validateCsrf(actor.ref(),"bound-csrf");
        verify(authorization).decide(eq(actor.ref()),eq("agent.execute"),isNull(),eq(Phase.ENTRY),isNull());
    }
    @Test void missingCsrfNeverStartsMutation()throws Exception {
        permit();doThrow(new SecurityFailure("CSRF_INVALID",403)).when(auth).validateCsrf(actor.ref(),null);
        var response=new MockHttpServletResponse();var calls=new AtomicInteger();
        filter.doFilter(request("POST","/ai/agent/chat",true),response,(q,s)->calls.incrementAndGet());
        assertEquals(403,response.getStatus());assertEquals(0,calls.get());
    }
    @Test void invisibleOwnerUsesUniformNotFound()throws Exception {
        permit();when(authorization.decide(any(),anyString(),any(),any(),isNull())).thenReturn(new AuthorizationDecision("decision","epoch:1",false,"RESOURCE_UNAVAILABLE"));
        var response=new MockHttpServletResponse();var calls=new AtomicInteger();
        filter.doFilter(request("GET","/conversations/12345678-1234-1234-1234-123456789abc",true),response,(q,s)->calls.incrementAndGet());
        assertEquals(404,response.getStatus());assertEquals(0,calls.get());assertFalse(response.getContentAsString().contains("RESOURCE_UNAVAILABLE"));
    }
    @Test void unknownAndLegacyEntriesAreClosedEvenForValidCookie()throws Exception {
        for(String path:Set.of("/new-controller","/ai/love_app/chat/sse","/ai/multiagent/events")) {
            var response=new MockHttpServletResponse();filter.doFilter(request("GET",path,true),response,(q,s)->fail("Closed entry reached controller"));
            assertEquals(404,response.getStatus());
        }
        verifyNoInteractions(auth,authorization);
    }
    @Test void crossOriginAndCrossSiteRequestsCannotReachAuthentication()throws Exception {
        for(String origin:Set.of("http://evil.example","null")) {
            var request=request("POST","/auth/login",false);request.addHeader("Origin",origin);
            var response=new MockHttpServletResponse();filter.doFilter(request,response,(q,s)->fail());assertEquals(403,response.getStatus());
        }
        var request=request("GET","/auth/status",false);request.addHeader("Sec-Fetch-Site","cross-site");
        var response=new MockHttpServletResponse();filter.doFilter(request,response,(q,s)->fail());assertEquals(403,response.getStatus());
        verifyNoInteractions(auth,authorization);
    }
    @Test void preflightAllowsOnlyReviewedMethodAndExplicitOriginWithoutBusinessExecution()throws Exception {
        var request=request("OPTIONS","/ai/agent/chat",false);request.addHeader("Origin","http://127.0.0.1:5174");request.addHeader("Access-Control-Request-Method","POST");
        var response=new MockHttpServletResponse();filter.doFilter(request,response,(q,s)->fail());
        assertEquals(204,response.getStatus());assertEquals("http://127.0.0.1:5174",response.getHeader("Access-Control-Allow-Origin"));
        assertNotEquals("*",response.getHeader("Access-Control-Allow-Headers"));verifyNoInteractions(auth,authorization);
    }
    @Test void absentRequestContextCannotInventAnActor() {
        assertEquals(401,assertThrows(SecurityFailure.class,()->AuthenticatedRequest.require(request("GET","/conversations",false))).status());
    }
}
