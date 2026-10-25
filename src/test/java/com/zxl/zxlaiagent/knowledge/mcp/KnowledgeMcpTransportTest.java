package com.zxl.zxlaiagent.knowledge.mcp;

import org.junit.jupiter.api.Test;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real loopback SSE transport, synthetic read-only handler; does not claim PostgreSQL validation. */
class KnowledgeMcpTransportTest {
    @Test void idleSseConnectionRemainsUsableBeyondServletDefaultTimeout()throws Exception {
        try(var server=KnowledgeMcpServer.start(0,new KnowledgeLookupTool(r->Map.of("hits",List.of())));
            var client=new KnowledgeMcpClient(URI.create("http://127.0.0.1:"+server.port()))) {
            client.refresh(Duration.ofSeconds(5),()->false);
            Thread.sleep(32000); // Regression: the default 30-second Servlet async timeout closed the actual SSE channel.
            assertFalse(client.call(KnowledgeMcpClient.EXPOSED_NAME,Map.of("query","after-idle"),Duration.ofSeconds(3),()->false).isError());
        }
    }
    @Test void actualSseDiscoveryCallDisconnectRecoveryAndRepeatedClose() throws Exception {
        var tool=new KnowledgeLookupTool(r->Map.of("query",r.query(),"hits",List.of()));
        var server=KnowledgeMcpServer.start(0,tool);
        int port=server.port();
        try(var client=new KnowledgeMcpClient(URI.create("http://127.0.0.1:"+port))) {
            client.refresh(Duration.ofSeconds(5),()->false);
            assertEquals("READY",client.status().state());
            assertEquals("2024-11-05",client.status().protocolVersion());
            assertEquals(List.of("knowledge__knowledge_lookup_v1"),client.tools().stream().map(KnowledgeMcpClient.DiscoveredTool::exposedName).toList());
            assertFalse(client.call("knowledge__knowledge_lookup_v1",Map.of("query","VPN"),Duration.ofSeconds(3),()->false).isError());
            server.close();server.close();
            var failure=assertThrows(KnowledgeMcpClient.Failure.class,()->client.refresh(Duration.ofMillis(500),()->false));
            assertEquals("UNAVAILABLE",client.status().state());assertFalse(failure.code().isBlank());
            try(var restored=KnowledgeMcpServer.start(port,new KnowledgeLookupTool(r->Map.of("query",r.query(),"hits",List.of())))) {
                client.refresh(Duration.ofSeconds(5),()->false);
                assertEquals("READY",client.status().state());
                assertFalse(client.call("knowledge__knowledge_lookup_v1",Map.of("query","VPN"),Duration.ofSeconds(3),()->false).isError());
            }
        } finally {server.close();}
    }
    @Test void cancelledReadDoesNotPublishLateSuccessAndHonorsOriginalBudget() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var finished=new CountDownLatch(1);
        try(var server=KnowledgeMcpServer.start(0,new KnowledgeLookupTool(r->{
            entered.countDown();try {release.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
            finally {finished.countDown();} return Map.of("hits",List.of());
        }));var client=new KnowledgeMcpClient(URI.create("http://127.0.0.1:"+server.port()))) {
            client.refresh(Duration.ofSeconds(5),()->false);
            var cancel=new AtomicBoolean();var pool=Executors.newSingleThreadExecutor();
            try {
                var response=pool.submit(()->client.call("knowledge__knowledge_lookup_v1",Map.of("query","VPN"),Duration.ofSeconds(2),cancel::get));
                assertTrue(entered.await(2,TimeUnit.SECONDS));cancel.set(true);
                var failed=assertThrows(ExecutionException.class,()->response.get(1,TimeUnit.SECONDS));
                assertEquals("MCP_CANCELLED",((KnowledgeMcpClient.Failure)failed.getCause()).code());
                release.countDown();assertTrue(finished.await(2,TimeUnit.SECONDS));
                assertFalse(client.call("knowledge__knowledge_lookup_v1",Map.of("query","later"),Duration.ofSeconds(2),()->false).isError());
            }finally {release.countDown();pool.shutdownNow();}
        }
    }
    @Test void clientRefusesToolsOutsideFixedAllowlist() {
        try(var client=new KnowledgeMcpClient(URI.create("http://127.0.0.1:18135"))) {
            var error=assertThrows(KnowledgeMcpClient.Failure.class,()->client.call("terminal",Map.of("query","anything"),Duration.ofSeconds(2),()->false));
            assertEquals("MCP_NOT_AUTHORIZED",error.code());
        }
    }
    @Test void fullClientQueueRejectsAdditionalCallWithoutStartingIt()throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();
        try(var server=KnowledgeMcpServer.start(0,new KnowledgeLookupTool(r->{
            calls.incrementAndGet();entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
            return Map.of("hits",List.of());
        }));var client=new KnowledgeMcpClient(URI.create("http://127.0.0.1:"+server.port()))) {
            client.refresh(Duration.ofSeconds(5),()->false);
            var cancel=new AtomicBoolean();var pool=Executors.newFixedThreadPool(10);var results=new ArrayList<Future<?>>();
            try {
                for(int n=0;n<10;n++)results.add(pool.submit(()->client.call(KnowledgeMcpClient.EXPOSED_NAME,Map.of("query","VPN"),Duration.ofSeconds(5),cancel::get)));
                assertTrue(entered.await(2,TimeUnit.SECONDS));
                long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
                while(client.status().queued()<8&&System.nanoTime()<end)Thread.sleep(10);
                assertEquals(8,client.status().queued());assertEquals(2,client.status().active());
                var full=assertThrows(KnowledgeMcpClient.Failure.class,()->client.call(KnowledgeMcpClient.EXPOSED_NAME,Map.of("query","overflow"),Duration.ofSeconds(1),()->false));
                assertEquals("MCP_CAPACITY",full.code());assertEquals(1,calls.get());
                cancel.set(true);for(var result:results)assertThrows(ExecutionException.class,()->result.get(2,TimeUnit.SECONDS));
            } finally {cancel.set(true);release.countDown();pool.shutdownNow();}
        }
    }
    @Test void realHttpOriginAndOversizedBodyAreRejectedBeforeSdkParsing()throws Exception {
        try(var server=KnowledgeMcpServer.start(0,new KnowledgeLookupTool(r->Map.of("hits",List.of())))) {
            var http=java.net.http.HttpClient.newHttpClient();String base="http://127.0.0.1:"+server.port();
            var origin=java.net.http.HttpRequest.newBuilder(URI.create(base+"/sse")).header("Origin","https://untrusted.example").GET().build();
            assertEquals(403,http.send(origin,java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode());
            var huge=java.net.http.HttpRequest.newBuilder(URI.create(base+"/mcp/message")).POST(java.net.http.HttpRequest.BodyPublishers.ofString("x".repeat(17409))).build();
            assertEquals(413,http.send(huge,java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode());
        }
    }
    @Test void serverLeaseRemainsOccupiedWhenTimedOutBackendIgnoresInterrupt()throws Exception {
        var entered=new CountDownLatch(2);var release=new CountDownLatch(1);var calls=new AtomicInteger();
        try(var tool=new KnowledgeLookupTool(r->{
            calls.incrementAndGet();entered.countDown();
            while(release.getCount()>0)try{release.await(20,TimeUnit.MILLISECONDS);}catch(InterruptedException ignored){/* intentionally uncooperative fake */}
            return Map.of("hits",List.of());
        })) {
            var pool=Executors.newFixedThreadPool(2);
            try {
                var first=pool.submit(()->tool.call(Map.of("query","first","budgetMs",300)));
                var second=pool.submit(()->tool.call(Map.of("query","second","budgetMs",300)));
                assertTrue(entered.await(2,TimeUnit.SECONDS));assertTrue(first.get(2,TimeUnit.SECONDS).isError());assertTrue(second.get(2,TimeUnit.SECONDS).isError());
                assertEquals(2,tool.activeQueries());
                assertTrue(tool.call(Map.of("query","queued","budgetMs",150)).isError());
                assertEquals(2,calls.get(),"Timed-out queued request must never start backend work");
                release.countDown();long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);
                while(tool.activeQueries()>0&&System.nanoTime()<end)Thread.sleep(10);
                assertEquals(0,tool.activeQueries());
            }finally {release.countDown();pool.shutdownNow();}
        }
    }
}
