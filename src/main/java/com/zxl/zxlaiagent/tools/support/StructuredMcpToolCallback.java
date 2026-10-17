package com.zxl.zxlaiagent.tools.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zxl.zxlaiagent.tools.model.ToolResult;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Map;

/** M6's stock callback drops isError. Keep the full protocol result at this boundary. */
public final class StructuredMcpToolCallback implements ToolCallback {
    private final McpSyncClient client;
    private final ToolDefinition definition;
    private final ObjectMapper mapper = new ObjectMapper();

    public StructuredMcpToolCallback(McpSyncClient client, McpSchema.Tool tool) {
        this.client = client;
        this.definition = ToolDefinition.builder().name(tool.name())
                .description(tool.description() == null ? "Remote MCP tool" : tool.description())
                .inputSchema(ToolResults.json(tool.inputSchema())).build();
    }

    @Override public ToolDefinition getToolDefinition() { return definition; }

    @Override public String call(String input) {
        Map<String, Object> arguments;
        try { arguments = mapper.readValue(input, new TypeReference<>() {}); }
        catch (Exception e) { throw new IllegalArgumentException("MCP 工具参数必须是 JSON 对象", e); }
        var result = client.callTool(new McpSchema.CallToolRequest(definition.name(), arguments));
        if (result == null) throw new IllegalStateException("MCP 工具未返回结果");
        if (Boolean.TRUE.equals(result.isError())) {
            return ToolResults.json(ToolResult.fail("MCP_TOOL_ERROR", "远程工具返回失败，请查看原始结果", result));
        }
        // A server can return this application's contract inside one text content block.
        if (result.content() != null && result.content().size() == 1
                && result.content().get(0) instanceof McpSchema.TextContent text) {
            try {
                var declared = ToolResults.read(text.text());
                return ToolResults.json(declared);
            } catch (IllegalStateException ignored) { /* Ordinary MCP content remains in data below. */ }
        }
        return ToolResults.json(ToolResult.ok("远程工具已返回，业务内容见 data", result));
    }
}
