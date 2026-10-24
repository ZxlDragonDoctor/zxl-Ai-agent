package com.zxl.zxlaiagent.knowledge;

import org.junit.jupiter.api.Test;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class HttpKnowledgeEmbeddingTest {
    @Test void retrievalBudgetStopsSlowHttpAndKeepsInterruptStatus() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            try { Thread.sleep(800); } catch(InterruptedException failure) { Thread.currentThread().interrupt(); }
            try { exchange.sendResponseHeaders(503,-1); } catch(java.io.IOException ignored) { } finally { exchange.close(); }
        });
        server.start();
        try {
            var adapter=new HttpKnowledgeEmbedding(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),"test-placeholder","model","v1",8);
            long start=System.nanoTime();
            assertThrows(IllegalStateException.class,()->adapter.embed("VPN",java.time.Duration.ofMillis(100)));
            assertTrue((System.nanoTime()-start)/1_000_000<700);
            Thread.currentThread().interrupt();
            try { assertThrows(IllegalStateException.class,()->adapter.embed("VPN",java.time.Duration.ofSeconds(1))); assertTrue(Thread.currentThread().isInterrupted()); }
            finally { Thread.interrupted(); }
        } finally { server.stop(0); }
    }
    @Test void compatibleHttpContractHasModelDimensionsAndNoFallback()throws Exception{
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/embeddings",exchange->{String body=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);assertTrue(body.contains("text-embedding-v3"));assertTrue(body.contains("dimensions"));assertEquals("Bearer test-placeholder",exchange.getRequestHeaders().getFirst("Authorization"));byte[] response="{\"data\":[{\"embedding\":[0.1,0.2,0.3]}]}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();});server.start();
        try{var adapter=new HttpKnowledgeEmbedding(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/embeddings"),"test-placeholder","text-embedding-v3","test-revision",3);assertArrayEquals(new float[]{.1f,.2f,.3f},adapter.embed("VPN"));assertEquals(3,adapter.dimensions());assertTrue(adapter.identity().contains("test-revision"));}finally{server.stop(0);}
    }
    @Test void missingCredentialAndBadResponseCannotSilentlyUseDemo()throws Exception{
        assertThrows(IllegalArgumentException.class,()->new HttpKnowledgeEmbedding(URI.create("https://example.invalid/embeddings"),"","model","v1",8));
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",exchange->{exchange.sendResponseHeaders(401,-1);exchange.close();});server.start();
        try{var adapter=new HttpKnowledgeEmbedding(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),"test-placeholder","model","v1",8);assertThrows(IllegalStateException.class,()->adapter.embed("VPN"));}finally{server.stop(0);}
    }
}
