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

class McpAdapterBoundaryTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private McpSchema.Tool tool() throws Exception {
        return mapper.readValue("""
          {"name":"lookup","description":"Read-only lookup","inputSchema":{"type":"object","properties":{
            "query":{"type":"string","minLength":1,"maxLength":100},
            "topK":{"type":"integer","minimum":1,"maximum":5}},"required":["query"],"additionalProperties":false}}
          """, McpSchema.Tool.class);
    }
    @Test void invalidJsonShapeTypesUnknownFieldsAndBoundsNeverInvokeRemote() throws Exception {
        var client = mock(McpSyncClient.class);
        var callback = new StructuredMcpToolCallback(client, tool());
        for (String input : new String[]{"null", "[]", "{}", "{", "{\"query\":1}",
                "{\"query\":\"vpn\",\"tenant\":\"other\"}", "{\"query\":\"vpn\",\"topK\":1.5}",
                "{\"query\":\"vpn\",\"topK\":6}", "{\"query\":\"vpn\",\"query\":\"second\"}",
                "{\"query\":\"vpn\"} false", "{\"query\":\"" + "x".repeat(20000) + "\"}"}) {
            var result = ToolResults.read(callback.call(input));
            assertFalse(result.path("success").asBoolean(), input.substring(0, Math.min(80, input.length())));
            assertEquals("MCP_INVALID_ARGUMENT", result.path("code").asText());
        }
        verifyNoInteractions(client);
    }
    @Test void malformedDeclaredContractIsFailureRatherThanGenericSuccess() throws Exception {
        var result = response("{\"success\":true}", false);
        assertEquals("MCP_INVALID_RESPONSE", invoke(result).path("code").asText());
    }
    @Test void malformedJsonLookingContractIsNotOrdinarySuccessfulText() throws Exception {
        assertEquals("MCP_INVALID_RESPONSE",invoke(response("{\"success\":true,",false)).path("code").asText());
        assertEquals("MCP_INVALID_RESPONSE",invoke(response("{\"success\":true,\"success\":false}",false)).path("code").asText());
    }
    @Test void blockAndByteLimitsFailClosed() throws Exception {
        var blocks = mapper.createArrayNode();
        for(int i=0;i<17;i++) blocks.add(mapper.createObjectNode().put("type","text").put("text","hi"));
        var response = mapper.createObjectNode().set("content",blocks);
        assertEquals("MCP_INVALID_RESPONSE", invoke(mapper.treeToValue(response, McpSchema.CallToolResult.class)).path("code").asText());
        assertEquals("MCP_INVALID_RESPONSE", invoke(response("中".repeat(50000),false)).path("code").asText());
    }
    @Test void protocolErrorWinsEvenForOversizedPayloadAndDoesNotExposePayload() throws Exception {
        var result = invoke(response("secret=" + "x".repeat(140000),true));
        assertEquals("MCP_TOOL_ERROR", result.path("code").asText());
        assertTrue(result.path("data").path("isError").asBoolean());
        assertFalse(result.toString().contains("secret="));
    }
    @Test void remoteFailureOnlyPublishesAllowlistedDiagnosticCodeAndNeverItsMessage() throws Exception {
        var result=invoke(response("{\"success\":false,\"code\":\"MCP_CAPACITY\",\"message\":\"secret-host\",\"data\":null}",true));
        assertEquals("MCP_TOOL_ERROR",result.path("code").asText());
        assertEquals("MCP_CAPACITY",result.path("data").path("remoteCode").asText());
        assertFalse(result.toString().contains("secret-host"));
    }
    @Test void nonTextContentIsNotForwardedToTheModel() throws Exception {
        var response = mapper.readValue("{\"content\":[{\"type\":\"image\",\"data\":\"c2VjcmV0\",\"mimeType\":\"image/png\"}],\"isError\":false}",McpSchema.CallToolResult.class);
        var result = invoke(response);
        assertFalse(result.toString().contains("c2VjcmV0"));
        assertTrue(result.path("data").path("content").path(0).path("omitted").asBoolean());
    }
    @Test void stableAliasKeepsRemoteOriginalNameAndDoesNotChangeIdentity() throws Exception {
        var client=mock(McpSyncClient.class);
        when(client.callTool(any(McpSchema.CallToolRequest.class))).thenReturn(response("hello",false));
        var callback=new StructuredMcpToolCallback(client,tool(),"knowledge","knowledge__lookup");
        assertEquals("knowledge__lookup",callback.getToolDefinition().name());
        assertEquals("lookup",callback.identity().originalName());
        assertEquals("knowledge",callback.identity().serverId());
        callback.call("{\"query\":\"vpn\",\"topK\":3}");
        verify(client).callTool(argThat(request -> request.name().equals("lookup")&&request.arguments().get("topK").equals(3)));
        assertThrows(IllegalArgumentException.class,()->new StructuredMcpToolCallback(client,tool(),"https://unknown","lookup"));
    }
    @Test void fullResultModelBudgetAndPreviewDoNotMutateOrMisrepresentSourceSpans() {
        String original=ToolResults.json(com.zxl.zxlaiagent.tools.model.ToolResult.ok("读取成功",
                java.util.Map.of("documentId","doc","startUtf16",0,"endUtf16",30000,"text","中".repeat(30000))));
        var views=StructuredMcpToolCallback.views(original);
        assertEquals(original,views.original());
        var model=ToolResults.read(views.model());
        assertTrue(model.path("modelTruncated").asBoolean());
        assertTrue(model.path("data").path("contentOmitted").asBoolean());
        assertFalse(model.path("data").has("startUtf16"));
        assertTrue(ToolResults.read(views.uiPreview()).path("displayTruncated").asBoolean());
        assertEquals(30000,ToolResults.read(original).path("data").path("text").asText().length());
    }
    @Test void declaredCredentialFieldsAreRejectedRatherThanEchoed() throws Exception {
        String text="{\"success\":true,\"code\":\"OK\",\"message\":\"done\",\"data\":{\"api_key\":\"never-echo-me\"}}";
        var result=invoke(response(text,false));
        assertEquals("MCP_INVALID_RESPONSE",result.path("code").asText());
        assertFalse(result.toString().contains("never-echo-me"));
    }
    @Test void unsupportedSchemaConstraintDoesNotSilentlyAllowUnvalidatedValue() throws Exception {
        var client=mock(McpSyncClient.class);
        var schemaTool=mapper.readValue("{\"name\":\"patterned\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\",\"pattern\":\"^safe$\"}}}}",McpSchema.Tool.class);
        var result=ToolResults.read(new StructuredMcpToolCallback(client,schemaTool).call("{\"query\":\"unsafe\"}"));
        assertEquals("MCP_INVALID_ARGUMENT",result.path("code").asText());
        verifyNoInteractions(client);
    }
    private McpSchema.CallToolResult response(String text,boolean error) throws Exception {
        var node=mapper.createObjectNode().put("isError",error);
        node.putArray("content").addObject().put("type","text").put("text",text);
        return mapper.treeToValue(node,McpSchema.CallToolResult.class);
    }
    private com.fasterxml.jackson.databind.JsonNode invoke(McpSchema.CallToolResult result) throws Exception {
        var client=mock(McpSyncClient.class);
        when(client.callTool(any(McpSchema.CallToolRequest.class))).thenReturn(result);
        return ToolResults.read(new StructuredMcpToolCallback(client,tool()).call("{\"query\":\"vpn\"}"));
    }
}
