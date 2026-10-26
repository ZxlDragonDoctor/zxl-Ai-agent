package com.zxl.zxlaiagent.research;

import com.zxl.zxlaiagent.knowledge.mcp.*;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ResearchDiscoverySnapshotTest {
    @Test void gatewayUsesReturnedDiscoverySnapshotInsteadOfRereadingMutableDirectory() {
        var client=mock(KnowledgeMcpClient.class);
        var tool=new McpSchema.Tool(KnowledgeMcpClient.EXPOSED_NAME,
                KnowledgeLookupTool.definition().description(),KnowledgeLookupTool.SCHEMA);
        var snapshot=List.of(new KnowledgeMcpClient.DiscoveredTool("knowledge",KnowledgeLookupTool.NAME,
                KnowledgeMcpClient.EXPOSED_NAME,"v1","digest",tool));
        when(client.discover(any(),any())).thenReturn(snapshot);
        // A separate refresh can temporarily clear the live directory after discovery returns.
        when(client.tools()).thenReturn(List.of());
        var gateway=new ResearchConfiguration().researchGateway(client);
        var found=gateway.discover(Duration.ofSeconds(2),()->false);
        assertEquals(1,found.size());assertEquals(KnowledgeMcpClient.EXPOSED_NAME,found.get(0).name());
        verify(client).discover(any(),any());verify(client,never()).tools();verify(client,never()).refresh(any(),any());
    }
    @Test void dispatchValidatesReviewedContractWithoutMutableDirectoryAndStillDeniesOtherTools() {
        var client=mock(KnowledgeMcpClient.class);
        when(client.call(anyString(),anyMap(),any(),any())).thenReturn(new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent("{\"success\":true,\"code\":\"OK\",\"message\":\"empty\",\"data\":{\"hits\":[]}}")),false));
        var gateway=new ResearchConfiguration().researchGateway(client);
        assertNotNull(gateway.call(KnowledgeMcpClient.EXPOSED_NAME,"{\"query\":\"VPN 401\"}",Duration.ofSeconds(2),()->false));
        assertThrows(KnowledgeMcpClient.Failure.class,()->gateway.call("admin", "{}",Duration.ofSeconds(2),()->false));
        assertThrows(IllegalArgumentException.class,()->gateway.call(KnowledgeMcpClient.EXPOSED_NAME,
                "{\"query\":\"VPN\",\"budgetMs\":100}",Duration.ofSeconds(2),()->false));
        verify(client,never()).tools();verify(client,times(1)).call(anyString(),anyMap(),any(),any());
    }
}
