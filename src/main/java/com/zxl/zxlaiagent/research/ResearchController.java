package com.zxl.zxlaiagent.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zxl.zxlaiagent.knowledge.mcp.*;
import com.zxl.zxlaiagent.tools.model.ToolResult;
import com.zxl.zxlaiagent.tools.support.*;
import org.springframework.web.bind.annotation.*;
import java.time.Duration;
import java.util.*;

/** Local teaching controls, fixed read-only scope. Production authentication is a later lesson. */
@RestController @RequestMapping("/ai/learning/mcp")
public class ResearchController {
    private final KnowledgeMcpClient client;private static final ObjectMapper JSON=new ObjectMapper();
    public ResearchController(KnowledgeMcpClient client){this.client=client;}
    @GetMapping("/status") public Map<String,Object> status(){
        var out=JSON.convertValue(client.status(),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});out.put("tools",client.tools());return out;
    }
    @PostMapping("/refresh") public Map<String,Object> refresh(){try{client.refresh(Duration.ofSeconds(5),()->Thread.currentThread().isInterrupted());}catch(KnowledgeMcpClient.Failure ignored){/* explicit unavailable state remains visible */}return status();}
    @PostMapping("/lookup") public com.fasterxml.jackson.databind.JsonNode lookup(@RequestBody String body){
        try{
            var input=StructuredMcpToolCallback.validateInput(body,KnowledgeLookupTool.definition());
            KnowledgeLookupTool.validate(input);
            long budget=input.containsKey("budgetMs")?((Number)input.get("budgetMs")).longValue():5000;
            return ToolResults.read(StructuredMcpToolCallback.adaptResult(client.call(KnowledgeMcpClient.EXPOSED_NAME,input,Duration.ofMillis(budget),()->Thread.currentThread().isInterrupted())));
        }catch(KnowledgeMcpClient.Failure failure){return ToolResults.read(ToolResults.json(ToolResult.fail(failure.code(),"MCP查询未完成；请核对服务、参数和预算")));}
        catch(IllegalArgumentException failure){return ToolResults.read(ToolResults.json(ToolResult.fail("MCP_INVALID_ARGUMENT","参数不符合knowledge_lookup_v1契约")));}
    }
}
