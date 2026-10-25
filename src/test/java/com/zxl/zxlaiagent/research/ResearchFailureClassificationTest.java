package com.zxl.zxlaiagent.research;

import com.zxl.zxlaiagent.agent.model.AgentExecutionEvent;
import com.zxl.zxlaiagent.knowledge.mcp.*;
import com.zxl.zxlaiagent.skills.*;
import com.zxl.zxlaiagent.tools.support.ToolResults;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class ResearchFailureClassificationTest {
    final ResearchRunner.Model model=new ResearchRunner.Model(){
        public ResearchRunner.Selection select(String q,List<ResearchRunner.Tool> t,Duration d,BooleanSupplier c){return new ResearchRunner.Selection("c1",KnowledgeMcpClient.EXPOSED_NAME,"{\"query\":\"VPN 401\"}");}
        public String answer(String q,List<com.fasterxml.jackson.databind.JsonNode> h,Duration d,BooleanSupplier c){throw new AssertionError("Failure must not generate an answer");}
    };
    ResearchRunner.Gateway gateway(String code,boolean remote){return new ResearchRunner.Gateway(){
        public List<ResearchRunner.Tool> discover(Duration d,BooleanSupplier c){return List.of(new ResearchRunner.Tool(KnowledgeMcpClient.EXPOSED_NAME,"lookup",KnowledgeLookupTool.SCHEMA));}
        public String call(String n,String a,Duration d,BooleanSupplier c){if(!remote)throw new KnowledgeMcpClient.Failure(code);return ToolResults.json(com.zxl.zxlaiagent.tools.model.ToolResult.fail("MCP_TOOL_ERROR","remote failed",Map.of("remoteCode",code)));}
    };}
    @Test void typedClientFailurePreservesOnlySafeCodeInPublicToolFailure() {
        for(String code:List.of("MCP_DEADLINE_EXCEEDED","MCP_CANCELLED","MCP_CAPACITY")){
            var events=new ArrayList<AgentExecutionEvent>();
            var failure=assertThrows(KnowledgeMcpClient.Failure.class,()->new ResearchRunner(gateway(code,false),model,(h,d,c)->h).run("VPN 401",Duration.ofSeconds(2),()->false,events::add));
            assertEquals(code,failure.code());assertEquals(code,ToolResults.read(events.get(events.size()-1).content()).path("code").asText());
        }
        var events=new ArrayList<AgentExecutionEvent>();
        assertThrows(RuntimeException.class,()->new ResearchRunner(gateway("secret=do-not-expose",false),model,(h,d,c)->h).run("VPN 401",Duration.ofSeconds(2),()->false,events::add));
        assertEquals("MCP_CALL_FAILED",ToolResults.read(events.get(events.size()-1).content()).path("code").asText());
        assertFalse(events.get(events.size()-1).content().contains("secret"));
    }
    @Test void adaptedRemoteDeadlineCancellationAndCapacityBecomeTypedFailuresAndSkillStatuses() {
        var statuses=Map.of("MCP_DEADLINE_EXCEEDED",SkillRunner.Status.TIMED_OUT,"MCP_CANCELLED",SkillRunner.Status.CANCELLED,"MCP_CAPACITY",SkillRunner.Status.CAPACITY);
        for(var entry:statuses.entrySet()){
            var result=new SkillRunner(new SkillRegistry(SkillCatalogue.builtIns()),new SkillExecutionPolicy(Set.of(KnowledgeMcpClient.EXPOSED_NAME)),gateway(entry.getKey(),true),model,(h,d,c)->h)
                    .run("it-ticket-analysis","v1","VPN 401",Duration.ofSeconds(2),SkillExecutionPolicy.demo(),()->false,e->{});
            assertEquals(entry.getValue(),result.status());assertNull(result.draft());
            if(result.status()==SkillRunner.Status.TIMED_OUT)assertTrue(result.advice().contains("本次步骤或整体预算已耗尽"));
        }
    }
    @Test void arbitraryRemoteTextDoesNotImpersonateDeadlineOrCancellation() {
        var result=new SkillRunner(new SkillRegistry(SkillCatalogue.builtIns()),new SkillExecutionPolicy(Set.of(KnowledgeMcpClient.EXPOSED_NAME)),gateway("MCP_DEADLINE_EXCEEDED secret",true),model,(h,d,c)->h)
                .run("it-ticket-analysis","v1","VPN 401",Duration.ofSeconds(2),SkillExecutionPolicy.demo(),()->false,e->{});
        assertEquals(SkillRunner.Status.FAILED,result.status());assertFalse(result.advice().contains("secret"));
    }
    @Test void arbitraryExceptionMessageDoesNotImpersonateTypedDeadline() {
        for(String text:List.of("MCP_DEADLINE_EXCEEDED user text","MCP_DEADLINE_EXCEEDED")){
            var original=gateway("unused",false);
            var forged=new ResearchRunner.Gateway(){
                public List<ResearchRunner.Tool> discover(Duration d,BooleanSupplier c){return original.discover(d,c);}
                public String call(String n,String a,Duration d,BooleanSupplier c){throw new IllegalStateException(text);}
            };
            var result=new SkillRunner(new SkillRegistry(SkillCatalogue.builtIns()),new SkillExecutionPolicy(Set.of(KnowledgeMcpClient.EXPOSED_NAME)),forged,model,(h,d,c)->h)
                    .run("it-ticket-analysis","v1","VPN 401",Duration.ofSeconds(2),SkillExecutionPolicy.demo(),()->false,e->{});
            assertEquals(SkillRunner.Status.FAILED,result.status());
        }
    }
    @Test void ownExactResearchDiagnosticSurvivesButArbitraryMessageIsNeverPublic() {
        for(String code:List.of("RESEARCH_EXPECTS_ONE_TOOL_SELECTION","RESEARCH_INVALID_ANSWER","RESEARCH_MISSING_CITATION","password=do-not-expose")){
            var failingModel=new ResearchRunner.Model(){
                public ResearchRunner.Selection select(String q,List<ResearchRunner.Tool> t,Duration d,BooleanSupplier c){throw new IllegalStateException(code);}
                public String answer(String q,List<com.fasterxml.jackson.databind.JsonNode> h,Duration d,BooleanSupplier c){throw new AssertionError();}
            };
            var result=new SkillRunner(new SkillRegistry(SkillCatalogue.builtIns()),new SkillExecutionPolicy(Set.of(KnowledgeMcpClient.EXPOSED_NAME)),gateway("unused",false),failingModel,(h,d,c)->h)
                    .run("it-ticket-analysis","v1","VPN 401",Duration.ofSeconds(2),SkillExecutionPolicy.demo(),()->false,e->{});
            assertEquals(code.startsWith("RESEARCH_")?code:"SKILL_EXECUTION_FAILED",result.code());assertFalse(result.advice().contains("password"));
        }
    }
}
