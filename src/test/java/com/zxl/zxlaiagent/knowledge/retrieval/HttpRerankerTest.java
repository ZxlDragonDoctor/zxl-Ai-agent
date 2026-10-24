package com.zxl.zxlaiagent.knowledge.retrieval;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class HttpRerankerTest {
    private RetrievalService.Hit hit(String id) {
        return new RetrievalService.Hit(id,"doc","rev",1,"VPN 809 检查网络",1,2,0,12,null,null,null,.01,null,List.of("keyword"));
    }
    @Test void providerIndicesMapOnlyToExistingCandidates() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/rerank",exchange->{
            var input=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
            assertTrue(input.contains("VPN 809")); assertEquals("Bearer local-contract",exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body="{\"output\":{\"results\":[{\"index\":1,\"relevance_score\":0.9},{\"index\":0,\"relevance_score\":0.2}]}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        try {
            var adapter=new HttpReranker(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/rerank"),"local-contract","qwen3-rerank","test-v1");
            var result=adapter.rank("VPN 809",List.of(hit("a"),hit("b")),Duration.ofSeconds(2));
            assertEquals("b",result.get(0).chunkId()); assertEquals(.9,result.get(0).score());
            assertEquals(2,Reranker.validate(List.of(hit("a"),hit("b")),result).size());
        } finally {server.stop(0);}
    }
    @Test void rejectsUnknownProviderIndexAndNeverAddsEvidence() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/rerank",exchange->{byte[] b="{\"output\":{\"results\":[{\"index\":99,\"relevance_score\":0.9}]}}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,b.length);exchange.getResponseBody().write(b);exchange.close();});server.start();
        try {var adapter=new HttpReranker(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/rerank"),"local-contract","qwen3-rerank","test-v1");assertThrows(IllegalStateException.class,()->adapter.rank("809",List.of(hit("a")),Duration.ofSeconds(2)));}
        finally {server.stop(0);}
    }
    @Test void endpointAndRemainingBudgetAreExplicit() {
        assertThrows(IllegalArgumentException.class,()->new HttpReranker(URI.create("http://example.com/rerank"),"x","qwen3-rerank","v1"));
        var adapter=new HttpReranker(URI.create("https://example.com/rerank"),"local-contract","qwen3-rerank","test-v1");
        assertThrows(IllegalArgumentException.class,()->adapter.rank("809",List.of(hit("a")),Duration.ZERO));
    }
}
