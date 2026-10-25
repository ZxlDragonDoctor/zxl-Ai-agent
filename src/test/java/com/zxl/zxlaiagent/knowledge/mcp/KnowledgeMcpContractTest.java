package com.zxl.zxlaiagent.knowledge.mcp;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeMcpContractTest {
    @Test void rejectsForgedIdentityUnknownFieldsAndTypesBeforeLookup() {
        var calls = new AtomicInteger();
        var tool = new KnowledgeLookupTool(request -> { calls.incrementAndGet(); return Map.of("hits", List.of()); });
        for (var input : List.of(Map.<String,Object>of("query","VPN","tenant","other"),
                Map.<String,Object>of("query","VPN","topK","3"), Map.<String,Object>of("query"," "),
                Map.<String,Object>of("query","VPN","topK",0), Map.<String,Object>of("query","VPN","mode","sql"))) {
            var result = tool.call(input);
            assertTrue(result.isError());
            assertTrue(((io.modelcontextprotocol.spec.McpSchema.TextContent)result.content().get(0)).text().contains("MCP_INVALID_ARGUMENT"));
        }
        assertEquals(0,calls.get());
    }
    @Test void emptySuccessAndUnavailableAreDifferent() {
        var empty = new KnowledgeLookupTool(request -> Map.of("hits",List.of())).call(Map.of("query","VPN"));
        assertFalse(empty.isError());
        var failure = new KnowledgeLookupTool(request -> { throw new IllegalStateException("password=do-not-expose"); }).call(Map.of("query","VPN"));
        assertTrue(failure.isError());
        String text=((io.modelcontextprotocol.spec.McpSchema.TextContent)failure.content().get(0)).text();
        assertTrue(text.contains("MCP_REMOTE_BUSINESS_FAILURE")); assertFalse(text.contains("password"));
    }
    @Test void schemaAndTransportTargetsAreFixed() {
        assertEquals("knowledge_lookup_v1",KnowledgeLookupTool.definition().name());
        assertThrows(IllegalArgumentException.class,()->new KnowledgeMcpClient(java.net.URI.create("https://example.com")));
        assertThrows(IllegalArgumentException.class,()->new KnowledgeMcpClient(java.net.URI.create("http://localhost:18135")));
        assertThrows(IllegalArgumentException.class,()->new KnowledgeMcpClient(java.net.URI.create("http://127.0.0.1:18135/evil")));
    }
    @Test void closedClientNeverReconnects() {
        var client=new KnowledgeMcpClient(java.net.URI.create("http://127.0.0.1:18135")); client.close();client.close();
        assertEquals("CLOSED",client.status().state());
        var failure=assertThrows(KnowledgeMcpClient.Failure.class,()->client.refresh(Duration.ofMillis(200),()->false));
        assertEquals("MCP_CLOSED",failure.code());assertEquals("CLOSED",client.status().state());
    }
    @Test void mainApplicationProfileDoesNotEnableStandaloneMcpMvcConfiguration() {
        var context=new org.springframework.web.context.support.AnnotationConfigWebApplicationContext();
        context.setServletContext(new org.springframework.mock.web.MockServletContext());
        context.register(KnowledgeMcpServer.MvcConfiguration.class);
        try {context.refresh();assertTrue(context.getBeansOfType(KnowledgeMcpServer.MvcConfiguration.class).isEmpty());}
        finally {context.close();}
    }
    @Test void writeUnknownOutcomeAndRemoteRetryHintNeverCauseBlindRetry(){
        assertFalse(KnowledgeMcpClient.retryAllowed(false,"MCP_NETWORK_FAILURE",0,5000));
        assertFalse(KnowledgeMcpClient.retryAllowed(true,"MCP_REMOTE_BUSINESS_FAILURE",0,5000));
        assertFalse(KnowledgeMcpClient.retryAllowed(true,"MCP_NETWORK_FAILURE",1,5000));
        assertFalse(KnowledgeMcpClient.retryAllowed(true,"MCP_NETWORK_FAILURE",0,100));
        assertTrue(KnowledgeMcpClient.retryAllowed(true,"MCP_NETWORK_FAILURE",0,5000));
    }
}
