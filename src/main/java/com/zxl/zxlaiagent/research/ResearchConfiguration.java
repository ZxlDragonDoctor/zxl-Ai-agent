package com.zxl.zxlaiagent.research;

import com.zxl.zxlaiagent.agent.stream.DashScopeAgentStreamer;
import com.zxl.zxlaiagent.knowledge.mcp.*;
import com.zxl.zxlaiagent.tools.support.*;
import org.springframework.context.annotation.*;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.function.BooleanSupplier;

@Configuration
public class ResearchConfiguration {
    @Bean(destroyMethod="close") public KnowledgeMcpClient knowledgeMcpClient(){return new KnowledgeMcpClient(URI.create(System.getenv().getOrDefault("LESSON15_MCP_ENDPOINT","http://127.0.0.1:18135")));}
    @Bean public ResearchRunner researchRunner(KnowledgeMcpClient client,DashScopeAgentStreamer streamer){
        var gateway=new ResearchRunner.Gateway(){
            public List<ResearchRunner.Tool> discover(Duration budget,BooleanSupplier cancelled){
                client.refresh(budget,cancelled);
                return client.tools().stream().map(t->new ResearchRunner.Tool(t.exposedName(),t.definition().description(),ToolResults.json(t.definition().inputSchema()))).toList();
            }
            public String call(String name,String input,Duration budget,BooleanSupplier cancelled){
                var tool=client.tools().stream().filter(t->t.exposedName().equals(name)).findFirst().orElseThrow(()->new IllegalStateException("MCP_NOT_AUTHORIZED"));
                var arguments=StructuredMcpToolCallback.validateInput(input,tool.definition());
                if(arguments.containsKey("budgetMs"))throw new IllegalArgumentException("Model cannot choose execution budget");
                return StructuredMcpToolCallback.views(StructuredMcpToolCallback.adaptResult(client.call(name,arguments,budget,cancelled))).original();
            }
        };
        return new ResearchRunner(gateway,new StreamingResearchModel(streamer),new McpEvidenceVerifier(URI.create(System.getenv().getOrDefault("LESSON15_EVIDENCE_GATEWAY","http://127.0.0.1:18132/ai/learning/knowledge/documents/"))));
    }
}
