package com.zxl.zxlaiagent.multiagent;

import com.zxl.zxlaiagent.controller.MultiAgentController;
import com.zxl.zxlaiagent.multiagent.security.*;
import com.zxl.zxlaiagent.security.*;
import com.zxl.zxlaiagent.security.web.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FormalMultiAgentControllerTest {
    final AuthenticatedActor actor=new AuthenticatedActor("synthetic","tenant","session",1,"employee",1);
    final ScopedMultiAgentService service=mock(ScopedMultiAgentService.class);
    MockMvc mvc(){
        var authentication=mock(AuthenticationService.class);var authorization=mock(AuthorizationService.class);
        when(authentication.authenticate("synthetic-cookie")).thenReturn(actor);
        doAnswer(i->{if(!"synthetic-csrf".equals(i.getArgument(1)))throw new SecurityFailure("CSRF_INVALID",403);return null;}).when(authentication).validateCsrf(any(),nullable(String.class));
        when(authorization.decide(any(),anyString(),nullable(ResourceRef.class),any(),nullable(String.class))).thenReturn(new AuthorizationDecision("synthetic","v1",true,"ALLOWED"));
        return MockMvcBuilders.standaloneSetup(new FormalMultiAgentController(service)).setControllerAdvice(new WebSecurityAdvice())
                .addFilters(new WebSecurityFilter(authentication,authorization,Set.of("http://127.0.0.1:5174"))).build();
    }
    Cookie cookie(){return new Cookie("ZXL_SESSION","synthetic-cookie");}
    @Test void missingFilterStillCannotSupplyItsOwnActor()throws Exception{
        MockMvcBuilders.standaloneSetup(new FormalMultiAgentController(service)).setControllerAdvice(new WebSecurityAdvice()).build()
                .perform(get("/ai/multiagent/status")).andExpect(status().isUnauthorized());verifyNoInteractions(service);
    }
    @Test void anonymousAndMissingCsrfNeverReachService()throws Exception{
        var mvc=mvc();mvc.perform(post("/api/ai/multiagent/runs").contextPath("/api").contentType("application/json").content("{\"goal\":\"x\"}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/ai/multiagent/runs").contextPath("/api").cookie(cookie()).contentType("application/json").content("{\"goal\":\"x\"}")).andExpect(status().isForbidden());verifyNoInteractions(service);
    }
    @Test void creationUsesOnlyVerifiedRequestActor()throws Exception{
        String id=UUID.randomUUID().toString();when(service.submit(eq(actor.ref()),eq("synthetic goal"),anyMap(),eq(5000L))).thenReturn(id);
        mvc().perform(post("/api/ai/multiagent/runs").contextPath("/api").cookie(cookie()).header("X-CSRF-Token","synthetic-csrf").contentType("application/json").content("{\"goal\":\"synthetic goal\",\"timeoutMs\":5000,\"context\":{\"product\":\"VPN\"}}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.executionId").value(id));
        verify(service).submit(actor.ref(),"synthetic goal",Map.of("product","VPN"),5000L);
    }
    @Test void malformedUnknownAndIdentityFieldsAreRejectedBeforeSubmission()throws Exception{
        var mvc=mvc();for(String body:List.of("{\"goal\":\"x\",\"actor\":{}}","{\"goal\":\"x\",\"tenantId\":\"foreign\"}","{\"goal\":\"x\",\"goal\":\"y\"}","{\"goal\":\"x\"} {}","{\"goal\":\"x\",\"timeoutMs\":1.5}","{\"goal\":\"x\",\"context\":[]}"))
            mvc.perform(post("/api/ai/multiagent/runs").contextPath("/api").cookie(cookie()).header("X-CSRF-Token","synthetic-csrf").contentType("application/json").content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void cancelOnlyReturnsAcknowledgement()throws Exception{
        String id=UUID.randomUUID().toString();when(service.cancel(actor.ref(),id)).thenReturn(new ScopedMultiAgentService.CancelAcknowledgement(id,"accepted"));
        mvc().perform(post("/api/ai/multiagent/runs/"+id+"/cancel").contextPath("/api").cookie(cookie()).header("X-CSRF-Token","synthetic-csrf"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("accepted")).andExpect(jsonPath("$.run").doesNotExist()).andExpect(jsonPath("$.report").doesNotExist());
    }
    @Test void readsEventPageWithVerifiedActorAndCursor() throws Exception {
        String id = UUID.randomUUID().toString();
        String path = "/api/ai/multiagent/runs/" + id + "/events";
        var event = new ScopedMultiAgentService.PublicRunEvent(3, "running", 100L, 0L, 1, null, null);
        when(service.eventsAfter(actor.ref(), id, 2L)).thenReturn(new ScopedMultiAgentService.EventPage(3L, List.of(event)));
        var mvc = mvc();
        mvc.perform(get(path).contextPath("/api").param("after", "2")).andExpect(status().isUnauthorized());
        mvc.perform(get(path).contextPath("/api").cookie(cookie()).param("after", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.lastSequence").value(3))
                .andExpect(jsonPath("$.events[0].sequence").value(3));
        mvc.perform(post(path).contextPath("/api").cookie(cookie())).andExpect(status().isNotFound());
        verify(service).eventsAfter(actor.ref(), id, 2L);
    }
    @Test void globalObserverAndHarnessRemainClosed()throws Exception{
        var mvc=mvc();for(String suffix:List.of("agents","events","harness/research-1","harness/benchmark"))mvc.perform(get("/api/ai/multiagent/"+suffix).contextPath("/api").cookie(cookie())).andExpect(status().isNotFound());verifyNoInteractions(service);
    }
    @Test void formalModeNeverRegistersLegacyController(){
        new ApplicationContextRunner().withPropertyValues("agent.security.mode=required").withBean(ScopedMultiAgentService.class,()->service)
                .withUserConfiguration(FormalMultiAgentController.class,MultiAgentController.class).run(context->{
                    assertEquals(1,context.getBeansOfType(FormalMultiAgentController.class).size());assertTrue(context.getBeansOfType(MultiAgentController.class).isEmpty());
                });
    }
}
