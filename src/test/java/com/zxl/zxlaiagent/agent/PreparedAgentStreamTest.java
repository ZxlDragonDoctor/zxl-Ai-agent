package com.zxl.zxlaiagent.agent;

import com.zxl.zxlaiagent.agent.stream.PreparedAgentStream;
import com.zxl.zxlaiagent.agent.stream.PreparedAgentStreamInterceptor;
import com.zxl.zxlaiagent.security.execution.OperationBoundary;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class PreparedAgentStreamTest {
    @RestController static class TransportController {
        final AtomicInteger started = new AtomicInteger();
        @GetMapping("/prepared") PreparedAgentStream prepared(HttpServletRequest request) {
            AtomicReference<PreparedAgentStream> ref = new AtomicReference<>();
            var stream = new PreparedAgentStream(5000, () -> {
                started.incrementAndGet();
                try { ref.get().send("transport-initialized"); }
                catch (Exception error) { throw new IllegalStateException(error); }
            }, () -> {});
            ref.set(stream);
            assertEquals(0, started.get());
            return PreparedAgentStreamInterceptor.attach(request, stream);
        }
    }

    @Test void actualSpringMvcHookRunsAfterEmitterHandlerInitialization() throws Exception {
        var controller = new TransportController(); var hook = new PreparedAgentStreamInterceptor();
        AtomicBoolean hookChecked = new AtomicBoolean();
        var mvc = MockMvcBuilders.standaloneSetup(controller).addInterceptors(new AsyncHandlerInterceptor() {
            @Override public void afterConcurrentHandlingStarted(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
                assertEquals(0, controller.started.get());
                hook.afterConcurrentHandlingStarted(request, response, handler);
                assertEquals(1, controller.started.get());
                // A synchronous send in the hook is already in the real MVC response. If the
                // emitter still buffered early frames this assertion would fail.
                assertTrue(((MockHttpServletResponse) response).getContentAsString().contains("transport-initialized"));
                hookChecked.set(true);
            }
        }).build();
        var result = mvc.perform(get("/prepared")).andReturn();
        assertTrue(result.getRequest().isAsyncStarted()); assertTrue(hookChecked.get());
    }

    @Test void preparationStartsNoWorkerAndAbandonReleasesExactlyOnce() throws Exception {
        AtomicInteger steps = new AtomicInteger(), completions = new AtomicInteger(), cleanups = new AtomicInteger();
        BaseAgent agent = new BaseAgent() {
            public String step() { steps.incrementAndGet(); return "private"; }
            protected void cleanup() { cleanups.incrementAndGet(); }
        };
        agent.bindOperationBoundary(OperationBoundary.unrestricted());
        agent.setCompletionListener(snapshot -> completions.incrementAndGet());
        var stream = agent.prepareStream("synthetic");
        assertEquals(0, steps.get()); assertTrue(agent.getMessageList().isEmpty());
        stream.abandon(); stream.abandon(); stream.activate();
        assertEquals(0, steps.get()); assertEquals(1, completions.get()); assertEquals(1, cleanups.get());
    }

    @Test void failedMvcRequestBeforeAsyncActivationAbandonsStream() throws Exception {
        AtomicInteger started = new AtomicInteger(), abandoned = new AtomicInteger();
        var stream = new PreparedAgentStream(5000, started::incrementAndGet, abandoned::incrementAndGet);
        var request = new MockHttpServletRequest();
        PreparedAgentStreamInterceptor.attach(request, stream);
        var hook = new PreparedAgentStreamInterceptor();
        hook.afterCompletion(request, new MockHttpServletResponse(), new Object(), new IllegalStateException("synthetic"));
        hook.afterConcurrentHandlingStarted(request, new MockHttpServletResponse(), new Object());
        assertEquals(0, started.get()); assertEquals(1, abandoned.get());
    }

    @RestController static class FailedController {
        final AtomicInteger started = new AtomicInteger(), abandoned = new AtomicInteger();
        @GetMapping("/failed") PreparedAgentStream failed(HttpServletRequest request) {
            PreparedAgentStreamInterceptor.attach(request,
                    new PreparedAgentStream(5000, started::incrementAndGet, abandoned::incrementAndGet));
            throw new IllegalStateException("synthetic controller failure");
        }
    }

    @Test void actualMvcControllerFailureReleasesPreparedRunWithoutStartingWorker() {
        var controller = new FailedController();
        var mvc = MockMvcBuilders.standaloneSetup(controller).addInterceptors(new PreparedAgentStreamInterceptor()).build();
        assertThrows(jakarta.servlet.ServletException.class, () -> mvc.perform(get("/failed")));
        assertEquals(0, controller.started.get()); assertEquals(1, controller.abandoned.get());
    }

    @Test void revocationBeforeActivationStartsNoStepAndPublishesNoSecret() throws Exception {
        var boundary = new AgentPublicationBoundaryTest.Boundary();
        AtomicInteger steps = new AtomicInteger(), captures = new AtomicInteger(); CountDownLatch cleaned = new CountDownLatch(1);
        BaseAgent agent = new BaseAgent() {
            public String step() { steps.incrementAndGet(); return "private"; }
            protected void cleanup() { cleaned.countDown(); }
        };
        agent.bindOperationBoundary(boundary); agent.setEventObserver(event -> captures.incrementAndGet());
        var stream = agent.prepareStream("synthetic"); boundary.revoke(); stream.activate();
        assertTrue(cleaned.await(2, TimeUnit.SECONDS));
        assertEquals(0, steps.get()); assertEquals(0, captures.get()); assertTrue(agent.getMessageList().isEmpty());
    }

    @Test void authorizedImmediateStreamApiFailsExplicitly() {
        BaseAgent agent = new BaseAgent() { public String step() { return "private"; } };
        agent.bindOperationBoundary(OperationBoundary.unrestricted());
        assertThrows(IllegalStateException.class, () -> agent.runStream("synthetic"));
    }
}
