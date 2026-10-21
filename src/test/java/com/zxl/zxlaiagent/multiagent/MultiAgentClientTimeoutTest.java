package com.zxl.zxlaiagent.multiagent;

import com.zxl.zxlaiagent.multiagent.config.MultiAgentModelFactory;
import com.zxl.zxlaiagent.multiagent.runtime.RuntimeSettings;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import java.net.InetSocketAddress;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class MultiAgentClientTimeoutTest {
    @Test void synchronousHttpRequestHasFiniteTimeoutWithoutPretendingToInterruptServer() throws Exception {
        CountDownLatch received = new CountDownLatch(1), release = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/slow", exchange -> {
            received.countDown();
            try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.close();
        }); server.start();
        try {
            RuntimeSettings settings = new RuntimeSettings(); settings.setRequestTimeoutMs(150);
            var client = RestClient.builder().requestFactory(MultiAgentModelFactory.requestFactory(settings)).build();
            assertThrows(org.springframework.web.client.ResourceAccessException.class,
                    () -> client.get().uri("http://127.0.0.1:" + server.getAddress().getPort() + "/slow").retrieve().body(String.class));
            assertTrue(received.await(1, TimeUnit.SECONDS)); assertEquals(1, release.getCount());
        } finally { release.countDown(); server.stop(0); }
    }
}
