package com.zxl.zxlaiagent.knowledge.mcp;

import com.zxl.zxlaiagent.tools.support.ToolResults;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeMcpFailureClassificationTest {
    private KnowledgeMcpClient.Failure classify(Throwable failure)throws Exception {
        var method=KnowledgeMcpClient.class.getDeclaredMethod("classify",Throwable.class);method.setAccessible(true);
        return (KnowledgeMcpClient.Failure)method.invoke(null,failure);
    }
    @Test void actualReactorSdkTimeoutCauseAndHttpTimeoutAreNotProtocolOrNetworkFailures()throws Exception {
        // M6 SDK DefaultMcpSession.sendRequest uses Mono.timeout, then the synchronous client blocks.
        var actual=assertThrows(RuntimeException.class,()->reactor.core.publisher.Mono.never().timeout(Duration.ofMillis(5)).block());
        assertEquals("MCP_DEADLINE_EXCEEDED",classify(actual).code());
        assertEquals("MCP_DEADLINE_EXCEEDED",classify(new java.net.http.HttpTimeoutException("opaque")).code());
        assertEquals("MCP_DEADLINE_EXCEEDED",classify(new CompletionException(new TimeoutException())).code());
        assertEquals("MCP_CANCELLED",classify(new CompletionException(new InterruptedException())).code());
        assertEquals("MCP_PROTOCOL_FAILURE",classify(new IllegalStateException("TimeoutException MCP_DEADLINE_EXCEEDED")).code());
        assertFalse(KnowledgeMcpClient.retryAllowed(true,"MCP_DEADLINE_EXCEEDED",0,5000));
        assertFalse(KnowledgeMcpClient.retryAllowed(false,"MCP_DEADLINE_EXCEEDED",0,5000));
    }
    @Test void shorterDiscoverySessionCapsLaterLongCallServerBudget()throws Exception {
        var observed=new AtomicLong();
        try(var server=KnowledgeMcpServer.start(0,new KnowledgeLookupTool(r->{observed.set(r.budgetMs());return Map.of("hits",List.of());}));
            var client=new KnowledgeMcpClient(URI.create("http://127.0.0.1:"+server.port()),Duration.ofSeconds(2))) {
            client.refresh(Duration.ofSeconds(8),()->false);
            assertFalse(client.call(KnowledgeMcpClient.EXPOSED_NAME,Map.of("query","VPN"),Duration.ofSeconds(4),()->false).isError());
            assertTrue(observed.get()>=100);assertTrue(observed.get()<=1500,"The <=2s SDK timeout reserves 500ms for a loaded transport to deliver the result");
        }
    }
    @Test void actualSlowHandlerReturnsExplicitDeadlineBeforeTheSdkTimesOut()throws Exception {
        try(var server=KnowledgeMcpServer.start(0,new KnowledgeLookupTool(r->{try{Thread.sleep(3000);}catch(InterruptedException e){Thread.currentThread().interrupt();}return Map.of("hits",List.of());}));
            var client=new KnowledgeMcpClient(URI.create("http://127.0.0.1:"+server.port()),Duration.ofSeconds(2))) {
            client.refresh(Duration.ofSeconds(8),()->false);
            var response=client.call(KnowledgeMcpClient.EXPOSED_NAME,Map.of("query","VPN"),Duration.ofSeconds(4),()->false);
            assertTrue(response.isError());
            assertEquals("MCP_DEADLINE_EXCEEDED",ToolResults.read(((McpSchema.TextContent)response.content().get(0)).text()).path("code").asText());
        }
    }
}
