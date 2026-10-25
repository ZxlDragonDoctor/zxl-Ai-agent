package com.zxl.zxlaiagent.knowledge.mcp;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Regression for SDK 0.7.0's unbounded legacy SSE sessions map; no model or PostgreSQL needed. */
class KnowledgeMcpSessionTest {
    @Test void twentyRefreshesKeepOnlyOneOwnedSseConnectionAndRemainCallable()throws Exception {
        var server=KnowledgeMcpServer.start(0,new KnowledgeLookupTool(r->Map.of("hits",List.of())));
        try(var client=new KnowledgeMcpClient(URI.create("http://127.0.0.1:"+server.port()))) {
            for(int attempt=0;attempt<20;attempt++) {
                client.refresh(Duration.ofSeconds(3),()->false);
                assertEquals(1,server.activeConnections(),"refresh "+attempt+" must replace and complete the old SSE session");
                assertFalse(client.call(KnowledgeMcpClient.EXPOSED_NAME,Map.of("query","refresh-"+attempt),Duration.ofSeconds(2),()->false).isError());
            }
        }finally {server.close();}
        assertEquals(0,server.activeConnections());assertEquals(0,server.pendingRequests());server.close();
    }
    @Test void replacedSessionCannotPostIntoNewConnection()throws Exception {
        var server=KnowledgeMcpServer.start(0,new KnowledgeLookupTool(r->Map.of("hits",List.of())));
        String base="http://127.0.0.1:"+server.port();var http=HttpClient.newHttpClient();
        try {
            var first=http.send(HttpRequest.newBuilder(URI.create(base+"/sse")).GET().build(),HttpResponse.BodyHandlers.ofInputStream());
            try(var old=first.body()) {
                String oldEndpoint=endpoint(old);
                var second=http.send(HttpRequest.newBuilder(URI.create(base+"/sse")).GET().build(),HttpResponse.BodyHandlers.ofInputStream());
                try(var fresh=second.body()) {
                    String newEndpoint=endpoint(fresh);assertNotEquals(oldEndpoint,newEndpoint,"Each ownership must receive a new session id");
                    var stale=http.send(HttpRequest.newBuilder(URI.create(base+oldEndpoint)).header("Content-Type","application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")).build(),HttpResponse.BodyHandlers.discarding());
                    assertEquals(410,stale.statusCode());assertEquals(1,server.activeConnections());
                    var missing=http.send(HttpRequest.newBuilder(URI.create(base+"/mcp/message")).header("Content-Type","application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{}")).build(),HttpResponse.BodyHandlers.discarding());
                    assertEquals(410,missing.statusCode());
                }
            }
        }finally{server.close();}
        assertEquals(0,server.activeConnections());
    }
    @Test void lateResponseFromReplacedConnectionCannotEnterNewOwnersToolResult()throws Exception {
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);var finished=new java.util.concurrent.CountDownLatch(1);
        try(var server=KnowledgeMcpServer.start(0,new KnowledgeLookupTool(r->{
            if("old".equals(r.query())){entered.countDown();try{release.await(3,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}finally{finished.countDown();}}
            return Map.of("query",r.query(),"hits",List.of());
        }))) {
            String base="http://127.0.0.1:"+server.port();var http=HttpClient.newHttpClient();
            var raw=http.send(HttpRequest.newBuilder(URI.create(base+"/sse")).GET().build(),HttpResponse.BodyHandlers.ofInputStream());
            try(var old=raw.body()) {
                String previous=endpoint(old);
                var body="{\"jsonrpc\":\"2.0\",\"id\":\"same\",\"method\":\"tools/call\",\"params\":{\"name\":\"knowledge_lookup_v1\",\"arguments\":{\"query\":\"old\"}}}";
                assertEquals(202,http.send(HttpRequest.newBuilder(URI.create(base+previous)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.discarding()).statusCode());
                assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));
                try(var next=new KnowledgeMcpClient(URI.create(base))) {
                    next.refresh(Duration.ofSeconds(3),()->false);
                    release.countDown();assertTrue(finished.await(2,java.util.concurrent.TimeUnit.SECONDS));
                    var current=next.call(KnowledgeMcpClient.EXPOSED_NAME,Map.of("query","new"),Duration.ofSeconds(2),()->false);
                    String text=((io.modelcontextprotocol.spec.McpSchema.TextContent)current.content().get(0)).text();
                    assertTrue(text.contains("\"query\":\"new\""));assertFalse(text.contains("\"query\":\"old\""));
                    assertEquals(1,server.activeConnections());assertEquals(0,server.pendingRequests());
                }
            }finally {release.countDown();}
        }
    }
    private String endpoint(InputStream input)throws IOException {
        var reader=new BufferedReader(new InputStreamReader(input,java.nio.charset.StandardCharsets.UTF_8));
        for(int n=0;n<8;n++){String line=reader.readLine();if(line==null)throw new EOFException("Missing SSE endpoint");if(line.startsWith("data:"))return line.substring(5).trim();}
        throw new IOException("Missing endpoint event");
    }
}
