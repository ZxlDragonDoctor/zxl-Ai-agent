package com.zxl.zxlaiagent.research;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.zxl.zxlaiagent.knowledge.mcp.*;
import com.zxl.zxlaiagent.security.*;
import com.zxl.zxlaiagent.security.web.AuthenticatedRequest;
import com.zxl.zxlaiagent.tools.model.ToolResult;
import com.zxl.zxlaiagent.tools.support.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.Duration;
import java.util.*;

@RestController
@RequestMapping("/ai/learning/mcp")
@ConditionalOnProperty(name="agent.security.mode",havingValue="required")
public final class FormalMcpController {
    private final FormalKnowledgeMcpClient client;
    private final AuthorizationService authorization;
    private final ObjectMapper json=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public FormalMcpController(FormalKnowledgeMcpClient client,AuthorizationService authorization){this.client=client;this.authorization=authorization;}
    @GetMapping("/status") public Map<String,Object> status(HttpServletRequest request){
        var actor=AuthenticatedRequest.require(request).ref();
        return metadata(actor,"knowledge.read");
    }
    private Map<String,Object> metadata(ActorRef actor,String action){
        return authorization.gate(actor,action,null,Phase.RESULT_PUBLISH,null,()->{
            Map<String,Object> out=json.convertValue(client.status(),new TypeReference<Map<String,Object>>(){});
            out.put("tools",client.tools());return out;
        });
    }
    @PostMapping("/refresh") public Map<String,Object> refresh(HttpServletRequest request){
        var actor=AuthenticatedRequest.require(request).ref();
        authorization.decide(actor,"knowledge.manage",null,Phase.ENTRY,null).requireAllowed();
        client.refresh(actor,Duration.ofSeconds(5),Thread.currentThread()::isInterrupted);
        return metadata(actor,"knowledge.manage");
    }
    @PostMapping("/lookup") public JsonNode lookup(HttpServletRequest request){
        var actor=AuthenticatedRequest.require(request).ref();
        var body=read(request);var model=new LinkedHashMap<String,Object>();
        if(!body.path("query").isTextual())throw invalid();model.put("query",body.path("query").textValue());
        if(body.has("topK")){if(!body.path("topK").isIntegralNumber()||!body.path("topK").canConvertToInt())throw invalid();model.put("topK",body.path("topK").intValue());}
        if(body.has("mode")){if(!body.path("mode").isTextual())throw invalid();model.put("mode",body.path("mode").textValue());}
        long budget=5000;
        if(body.has("budgetMs")){if(!body.path("budgetMs").isIntegralNumber()||!body.path("budgetMs").canConvertToLong())throw invalid();budget=body.path("budgetMs").longValue();}
        if(budget<100||budget>10000)throw invalid();
        final Map<String,Object> arguments;
        try{arguments=FormalKnowledgeMcpProtocol.modelArguments(model);}catch(IllegalArgumentException bad){throw invalid();}
        authorization.decide(actor,"knowledge.read",null,Phase.ENTRY,null).requireAllowed();
        var result=client.call(actor,FormalKnowledgeMcpProtocol.EXPOSED_NAME,arguments,Duration.ofMillis(budget),Thread.currentThread()::isInterrupted);
        return authorization.gate(actor,"knowledge.read",null,Phase.RESULT_PUBLISH,null,()->ToolResults.read(StructuredMcpToolCallback.adaptResult(result)));
    }
    private JsonNode read(HttpServletRequest request){
        try{
            byte[] bytes=request.getInputStream().readNBytes(16385);if(bytes.length==0||bytes.length>16384)throw invalid();
            var node=json.readTree(bytes);if(node==null||!node.isObject())throw invalid();
            var fields=node.fieldNames();while(fields.hasNext())if(!Set.of("query","topK","mode","budgetMs").contains(fields.next()))throw invalid();
            return node;
        }catch(java.io.IOException bad){throw invalid();}
    }
    private static SecurityFailure invalid(){return new SecurityFailure("MCP_INVALID_ARGUMENT",400);}
    @ExceptionHandler(FormalKnowledgeMcpClient.Failure.class) public ResponseEntity<JsonNode> failure(FormalKnowledgeMcpClient.Failure failed){
        int status="MCP_NOT_AUTHORIZED".equals(failed.code())?403:"MCP_INVALID_ARGUMENT".equals(failed.code())?400:503;
        return ResponseEntity.status(status).body(ToolResults.read(ToolResults.json(ToolResult.fail(failed.code(),"MCP查询未完成，请核对当前权限、服务和预算"))));
    }
}
