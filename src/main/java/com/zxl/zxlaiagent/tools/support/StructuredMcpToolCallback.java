package com.zxl.zxlaiagent.tools.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zxl.zxlaiagent.tools.model.ToolResult;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded MCP boundary. Byte limits apply AFTER SDK response allocation, not streaming. */
public final class StructuredMcpToolCallback implements ToolCallback {
    public static final int MAX_INPUT_BYTES = 16_384;
    public static final int MAX_RESPONSE_BYTES = 131_072;
    public static final int MAX_CONTENT_BLOCKS = 16;
    public static final int MAX_MODEL_BYTES = 24_576;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final McpSyncClient client;
    private final ToolDefinition definition;
    private final McpSchema.Tool tool;
    private final String serverId;

    public StructuredMcpToolCallback(McpSyncClient client, McpSchema.Tool tool) {
        this(client,tool,"legacy",tool.name());
    }

    /** Deployment identity is never inferred from an untrusted remote description. */
    public StructuredMcpToolCallback(McpSyncClient client, McpSchema.Tool tool,String serverId,String exposedName) {
        this.client = Objects.requireNonNull(client);
        this.tool = Objects.requireNonNull(tool);
        this.serverId = identifier(serverId);
        identifier(tool.name());
        identifier(exposedName);
        String description=tool.description()==null?"Remote MCP tool":tool.description();
        if(description.length()>2000) description=description.substring(0,2000);
        this.definition = ToolDefinition.builder().name(exposedName).description(description)
                .inputSchema(ToolResults.json(tool.inputSchema())).build();
    }

    public record Identity(String serverId,String originalName,String exposedName,String inputSchema) { }
    public Identity identity() { return new Identity(serverId,tool.name(),definition.name(),definition.inputSchema()); }
    @Override public ToolDefinition getToolDefinition() { return definition; }
    @Override public String call(String input) { return invokeDetailed(input).model(); }

    public ResultViews invokeDetailed(String input) {
        Map<String,Object> arguments;
        try { arguments=validateInput(input,tool); }
        catch(IllegalArgumentException e) { return views(failure("MCP_INVALID_ARGUMENT","MCP 参数不符合受支持的工具契约")); }
        // Runtime owns network deadline, cancellation and retry; no additional retries here.
        return views(adaptResult(client.callTool(new McpSchema.CallToolRequest(tool.name(),arguments))));
    }

    public static Map<String,Object> validateInput(String input,McpSchema.Tool tool) {
        return McpInputValidator.validate(input,tool,MAX_INPUT_BYTES);
    }

    public static String adaptResult(McpSchema.CallToolResult result) {
        if(result==null) return failure("MCP_INVALID_RESPONSE","MCP 工具未返回结果");
        // Error priority precedes size parsing; arbitrary remote errors are not echoed publicly.
        if(Boolean.TRUE.equals(result.isError())) {
            Map<String,Object> diagnostic=new LinkedHashMap<>();
            diagnostic.put("isError",true);
            diagnostic.put("contentOmitted",true);
            String remoteCode=safeRemoteCode(result);
            if(remoteCode!=null) diagnostic.put("remoteCode",remoteCode);
            return ToolResults.json(ToolResult.fail("MCP_TOOL_ERROR","远程工具返回失败",diagnostic));
        }
        if(result.content()==null||result.content().size()>MAX_CONTENT_BLOCKS)
            return failure("MCP_INVALID_RESPONSE","MCP 响应内容块数量不合法");
        String encoded=ToolResults.json(result);
        if(bytes(encoded)>MAX_RESPONSE_BYTES) return failure("MCP_INVALID_RESPONSE","MCP 响应超过字节上限");
        try {
            if(result.content().size()==1&&result.content().get(0) instanceof McpSchema.TextContent text) {
                JsonNode declared;
                try { declared=McpInputValidator.parse(text.text()); }
                catch(Exception ignored) {
                    if(text.text()!=null && (text.text().stripLeading().startsWith("{")||text.text().stripLeading().startsWith("[")))
                        return failure("MCP_INVALID_RESPONSE","MCP JSON 内容不合法");
                    declared=null;
                }
                if(declared!=null&&declared.isObject()&&(declared.has("success")||declared.has("code")||declared.has("message")||declared.has("data"))) {
                    JsonNode valid=ToolResults.read(text.text());
                    if(valid.size()!=4||valid.path("message").asText().length()>1000||valid.path("code").asText().length()>80)
                        throw new IllegalArgumentException("contract fields");
                    inspectPublic(valid,0,new int[]{0});
                    return ToolResults.json(valid);
                }
            }
            var publicResult=MAPPER.createObjectNode().put("isError",false);
            var blocks=publicResult.putArray("content");
            for(McpSchema.Content content:result.content()) {
                if(content instanceof McpSchema.TextContent text) blocks.addObject().put("type","text").put("text",text.text());
                else blocks.addObject().put("type","non-text").put("omitted",true);
            }
            return ToolResults.json(ToolResult.ok("远程工具已返回，业务内容尚未核验",publicResult));
        } catch(Exception e) { return failure("MCP_INVALID_RESPONSE","MCP 业务结果契约不合法或含禁止公开字段"); }
    }

    /** Diagnostic only: it never grants retry permission or changes the protocol failure status. */
    private static String safeRemoteCode(McpSchema.CallToolResult result) {
        try {
            if(result.content()==null||result.content().size()!=1||!(result.content().get(0) instanceof McpSchema.TextContent text)
                    ||text.text()==null||bytes(text.text())>4096) return null;
            JsonNode contract=ToolResults.read(text.text());
            if(contract.path("success").asBoolean())return null;
            String code=contract.path("code").asText();
            return Set.of("MCP_INVALID_ARGUMENT","MCP_UNAUTHORIZED","MCP_NOT_VISIBLE","MCP_DEADLINE_EXCEEDED",
                    "MCP_CANCELLED","MCP_CAPACITY","MCP_REMOTE_BUSINESS_FAILURE","MCP_INVALID_RESPONSE").contains(code)?code:null;
        } catch(Exception ignored) { return null; }
    }

    private static void inspectPublic(JsonNode node,int depth,int[] count) {
        if(depth>12||++count[0]>4096) throw new IllegalArgumentException("response shape");
        if(node.isObject()) {
            var fields=node.fields();
            while(fields.hasNext()) {
                var field=fields.next();
                String key=field.getKey().toLowerCase(Locale.ROOT).replace("_","").replace("-","");
                if(key.length()>128||Set.of("authorization","apikey","password","secret","accesstoken","refreshtoken").contains(key))
                    throw new IllegalArgumentException("private field");
                inspectPublic(field.getValue(),depth+1,count);
            }
        } else if(node.isArray()) for(JsonNode item:node) inspectPublic(item,depth+1,count);
    }

    /** Full accepted result, model budget and UI preview are separate immutable values. */
    public record ResultViews(String original,String model,String uiPreview) { }
    public static ResultViews views(String original) {
        String model=original;
        if(bytes(original)>MAX_MODEL_BYTES) {
            ObjectNode limited=(ObjectNode) ToolResults.read(original).deepCopy();
            // Do not preserve partial text with a full source span pretending to be usable evidence.
            limited.set("data",MAPPER.createObjectNode().put("contentOmitted",true)
                    .put("reason","MODEL_RESULT_BUDGET").put("originalBytes",bytes(original)));
            limited.put("modelTruncated",true);
            model=ToolResults.json(limited);
        }
        return new ResultViews(original,model,ToolResults.preview(original));
    }
    private static String failure(String code,String message) { return ToolResults.json(ToolResult.fail(code,message)); }
    private static int bytes(String value) { return value.getBytes(StandardCharsets.UTF_8).length; }
    private static String identifier(String value) {
        if(value==null||!value.matches("[A-Za-z0-9_-]{1,100}")) throw new IllegalArgumentException("MCP identity");
        return value;
    }
}
