package com.zxl.zxlaiagent.knowledge.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zxl.zxlaiagent.knowledge.gateway.ScopedKnowledgeClient;
import com.zxl.zxlaiagent.knowledge.retrieval.RetrievalService;
import com.zxl.zxlaiagent.security.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.Set;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class FormalMcpWorkerLookupTest {
    private static final ActorRef ACTOR = new ActorRef("subject", "tenant", "session", 1);
    private static final RetrievalService.Request QUERY = new RetrievalService.Request("VPN", "keyword", 3, 20, false, false, 1000);
    private FormalKnowledgeMcpTool.Lookup lookup(ScopedKnowledgeClient client) throws Exception {
        return (FormalKnowledgeMcpTool.Lookup) Class.forName("com.zxl.zxlaiagent.knowledge.mcp.FormalKnowledgeMcpWorker")
                .getDeclaredMethod("lookup", ScopedKnowledgeClient.class).invoke(null, client);
    }
    @Test void lookupDelegatesCurrentActorWithBusinessJsonOnly() throws Exception {
        var client = mock(ScopedKnowledgeClient.class);
        when(client.exchange(eq(ACTOR), eq("POST"), eq("/ai/learning/knowledge/retrieval"), isNull(), any(),
                eq(Duration.ofSeconds(1)), any(), anyList(), isNull())).thenAnswer(call -> {
            var body = new ObjectMapper().readTree((byte[]) call.getArgument(4));
            assertEquals("VPN", body.path("query").asText());
            assertEquals(Set.of("query", "mode", "topK", "candidateLimit", "rerank", "rewrite", "budgetMs"),
                    new ObjectMapper().convertValue(body, Map.class).keySet());
            return new ScopedKnowledgeClient.Response(200, "{\"hits\":[]}".getBytes());
        });
        assertTrue(new ObjectMapper().valueToTree(lookup(client).retrieve(ACTOR, QUERY)).path("hits").isArray());
        verify(client).exchange(eq(ACTOR), eq("POST"), eq("/ai/learning/knowledge/retrieval"), isNull(), any(), any(), any(), anyList(), isNull());
    }
    @Test void workerDenialIsTypedAndDoesNotPublishRemoteBody() throws Exception {
        var client = mock(ScopedKnowledgeClient.class);
        when(client.exchange(any(), any(), any(), any(), any(), any(), any(), anyList(), any()))
                .thenReturn(new ScopedKnowledgeClient.Response(403, "private remote detail".getBytes()));
        var denied = assertThrows(SecurityFailure.class, () -> lookup(client).retrieve(ACTOR, QUERY));
        assertEquals(403, denied.status()); assertFalse(denied.getMessage().contains("private"));
    }
    @Test void workerOutageIsNeverReportedAsEmptyRetrieval() throws Exception {
        var client = mock(ScopedKnowledgeClient.class);
        when(client.exchange(any(), any(), any(), any(), any(), any(), any(), anyList(), any()))
                .thenReturn(new ScopedKnowledgeClient.Response(503, "{}".getBytes()));
        assertThrows(IllegalStateException.class, () -> lookup(client).retrieve(ACTOR, QUERY));
        verify(client, times(1)).exchange(any(), any(), any(), any(), any(), any(), any(), anyList(), any());
    }
    @Test void malformedWorkerContractIsRejected() throws Exception {
        var client = mock(ScopedKnowledgeClient.class);
        when(client.exchange(any(), any(), any(), any(), any(), any(), any(), anyList(), any()))
                .thenReturn(new ScopedKnowledgeClient.Response(200, "{\"unexpected\":true}".getBytes()));
        assertThrows(IllegalStateException.class, () -> lookup(client).retrieve(ACTOR, QUERY));
    }
}
