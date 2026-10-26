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
    @Bean public ResearchRunner.Gateway researchGateway(KnowledgeMcpClient client){
        return new ResearchRunner.Gateway(){
            public List<ResearchRunner.Tool> discover(Duration budget,BooleanSupplier cancelled){
                return client.discover(budget,cancelled).stream().map(t->new ResearchRunner.Tool(t.exposedName(),t.definition().description(),ToolResults.json(t.definition().inputSchema()))).toList();
            }
            public String call(String name,String input,Duration budget,BooleanSupplier cancelled){
                if(!KnowledgeMcpClient.EXPOSED_NAME.equals(name))throw new KnowledgeMcpClient.Failure("MCP_NOT_AUTHORIZED");
                // The supported contract is fixed in reviewed Java code; do not read a temporary
                // empty live directory between another request's discard/connect operations.
                var arguments=StructuredMcpToolCallback.validateInput(input,KnowledgeLookupTool.definition());
                if(arguments.containsKey("budgetMs"))throw new IllegalArgumentException("Model cannot choose execution budget");
                return StructuredMcpToolCallback.views(StructuredMcpToolCallback.adaptResult(client.call(name,arguments,budget,cancelled))).original();
            }
        };
    }
    @Bean public ResearchRunner.Model researchModel(DashScopeAgentStreamer streamer) { return new StreamingResearchModel(streamer); }
    @Bean public ResearchRunner.Verifier researchVerifier() { return new McpEvidenceVerifier(URI.create(System.getenv().getOrDefault("LESSON15_EVIDENCE_GATEWAY","http://127.0.0.1:18132/ai/learning/knowledge/documents/"))); }
    @Bean public ResearchRunner researchRunner(ResearchRunner.Gateway gateway, ResearchRunner.Model model, ResearchRunner.Verifier verifier) { return new ResearchRunner(gateway, model, verifier); }
}
