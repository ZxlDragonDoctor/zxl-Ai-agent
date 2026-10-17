package com.zxl.zxlaiagent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zxl.zxlaiagent.tools.support.StructuredMcpToolCallback;
import com.zxl.zxlaiagent.tools.support.ToolResults;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class McpResultContractTest {
    @Test void longResultPreviewPreservesItsEnvelope() {
        String raw = ToolResults.json(com.zxl.zxlaiagent.tools.model.ToolResult.ok("读取成功",
                java.util.Map.of("path", "long.txt", "content", "a".repeat(20000))));
        var preview = ToolResults.read(ToolResults.preview(raw));
        assertTrue(preview.path("success").asBoolean());
        assertEquals("OK", preview.path("code").asText());
        assertTrue(preview.path("displayTruncated").asBoolean());
        assertTrue(preview.path("data").path("content").asText().length() < 20000);
        assertEquals(20000, ToolResults.read(raw).path("data").path("content").asText().length());
    }
    private final ObjectMapper mapper = new ObjectMapper();
    @Test void protocolErrorIsPreservedEvenWhenTextLooksSuccessful() throws Exception {
        var node = invoke("{\"content\":[{\"type\":\"text\",\"text\":\"done\"}],\"isError\":true}");
        assertFalse(node.path("success").asBoolean());
        assertEquals("MCP_TOOL_ERROR", node.path("code").asText());
        assertTrue(node.path("data").path("isError").asBoolean());
    }
    @Test void normalProtocolResultKeepsItsPayload() throws Exception {
        var node = invoke("{\"content\":[{\"type\":\"text\",\"text\":\"hello\"}],\"isError\":false}");
        assertTrue(node.path("success").asBoolean());
        assertEquals("hello", node.path("data").path("content").path(0).path("text").asText());
    }
    @Test void malformedContractCannotBeMistakenForSuccess() {
        assertThrows(IllegalStateException.class, () -> ToolResults.read("\"OK\""));
        assertThrows(IllegalStateException.class, () -> ToolResults.read("{\"success\":true}"));
    }
    private com.fasterxml.jackson.databind.JsonNode invoke(String result) throws Exception {
        var client = mock(McpSyncClient.class);
        when(client.callTool(any(McpSchema.CallToolRequest.class)))
                .thenReturn(mapper.readValue(result, McpSchema.CallToolResult.class));
        var tool = mapper.readValue("{\"name\":\"remote\",\"description\":\"remote\",\"inputSchema\":{\"type\":\"object\",\"properties\":{}}}", McpSchema.Tool.class);
        return ToolResults.read(new StructuredMcpToolCallback(client, tool).call("{}"));
    }
}
