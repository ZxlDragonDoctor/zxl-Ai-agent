package com.zxl.zxlaiagent.agent.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.zxl.zxlaiagent.security.Phase;
import com.zxl.zxlaiagent.security.execution.OperationBoundary;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

class DashScopeAuthorizationTest {
    static final class Fixture implements AutoCloseable {
        final CountDownLatch arrived = new CountDownLatch(1), release = new CountDownLatch(1);
        final AtomicInteger requests = new AtomicInteger();
        final HttpServer server;
        Fixture() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/chat/completions", exchange -> {
                requests.incrementAndGet(); arrived.countDown();
                try {
                    if (!release.await(8, TimeUnit.SECONDS)) return;
                    byte[] bytes = ("data: {\"choices\":[{\"delta\":{\"content\":\"safe\"},\"finish_reason\":\"stop\"}]}\n\n"
                            + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                finally { exchange.close(); }
            });
            server.start();
        }
        DashScopeAgentStreamer streamer() {
            return new DashScopeAgentStreamer(new ObjectMapper(), "synthetic-token",
                    "http://127.0.0.1:" + server.getAddress().getPort(), "synthetic-model", false, 0);
        }
        public void close() { release.countDown(); server.stop(0); }
    }

    @Test void deniedDispatchNeverSubscribesOrCallsTransport() throws Exception {
        try (var fixture = new Fixture()) {
            OperationBoundary denied = new OperationBoundary() {
                public void check(Phase phase) { throw new SecurityException("denied"); }
                public <T> T start(Phase phase, Supplier<T> operation) { throw new SecurityException("denied"); }
            };
            assertThrows(SecurityException.class, () -> fixture.streamer().stream("system", List.of(), new ToolCallback[0],
                    (type, text) -> fail("No delta allowed"), () -> false, denied));
            assertEquals(0, fixture.requests.get());
        }
    }

    @Test void subscriptionStartReturnsBeforeRemoteResponseAndNoGateIsHeldWhileWaiting() throws Exception {
        try (var fixture = new Fixture()) {
            var inStart = new AtomicBoolean(); var starts = new AtomicInteger();
            OperationBoundary boundary = new OperationBoundary() {
                public void check(Phase phase) { assertFalse(inStart.get()); }
                public <T> T start(Phase phase, Supplier<T> operation) {
                    assertEquals(Phase.MODEL_DISPATCH, phase); starts.incrementAndGet(); inStart.set(true);
                    try { return operation.get(); } finally { inStart.set(false); }
                }
            };
            var pending = CompletableFuture.supplyAsync(() -> fixture.streamer().stream("system", List.of(), new ToolCallback[0],
                    (type, text) -> {}, () -> false, boundary));
            assertTrue(fixture.arrived.await(5, TimeUnit.SECONDS));
            assertFalse(inStart.get()); assertFalse(pending.isDone());
            fixture.release.countDown();
            assertEquals("safe", pending.get(5, TimeUnit.SECONDS).getResult().getOutput().getText());
            assertEquals(1, starts.get());
        }
    }

    @Test void revocationDuringRemoteWaitStopsDeltaAndFinalResponse() throws Exception {
        try (var fixture = new Fixture()) {
            var allowed = new AtomicBoolean(true); var deltas = new AtomicInteger();
            OperationBoundary boundary = new OperationBoundary() {
                public void check(Phase phase) { if (!allowed.get()) throw new SecurityException("revoked"); }
                public <T> T start(Phase phase, Supplier<T> operation) { check(phase); return operation.get(); }
            };
            var pending = CompletableFuture.supplyAsync(() -> fixture.streamer().stream("system", List.of(), new ToolCallback[0],
                    (type, text) -> deltas.incrementAndGet(), () -> false, boundary));
            assertTrue(fixture.arrived.await(5, TimeUnit.SECONDS)); allowed.set(false); fixture.release.countDown();
            assertInstanceOf(SecurityException.class, assertThrows(ExecutionException.class, () -> pending.get(5, TimeUnit.SECONDS)).getCause());
            assertEquals(0, deltas.get());
        }
    }

    @Test void cancellationWhileServerSendsNoFramesStopsWaitingPromptly() throws Exception {
        try (var fixture = new Fixture()) {
            var cancelled = new AtomicBoolean();
            var pending = CompletableFuture.supplyAsync(() -> fixture.streamer().stream("system", List.of(), new ToolCallback[0],
                    (type, text) -> fail("No delta expected"), cancelled::get, OperationBoundary.unrestricted()));
            assertTrue(fixture.arrived.await(5, TimeUnit.SECONDS));
            cancelled.set(true);
            assertInstanceOf(CancellationException.class,
                    assertThrows(ExecutionException.class, () -> pending.get(1, TimeUnit.SECONDS)).getCause());
        }
    }

    @Test void formalCallerCannotOmitBoundary() throws Exception {
        try (var fixture = new Fixture()) {
            assertThrows(NullPointerException.class, () -> fixture.streamer().stream("system", List.of(), new ToolCallback[0],
                    (type, text) -> {}, () -> false, null));
            assertEquals(0, fixture.requests.get());
        }
    }
}
