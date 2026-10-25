package com.zxl.zxlaiagent.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zxl.zxlaiagent.agent.model.AgentExecutionEvent;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class ResearchRunnerTest {
    final ObjectMapper json = new ObjectMapper();
    @Test void selectedAllowedToolExecutesBeforeAnswerAndIsRevalidated() throws Exception {
        AtomicInteger calls = new AtomicInteger(), checks = new AtomicInteger();
        var events = new ArrayList<AgentExecutionEvent>();
        var runner = runner(calls, checks, false, false);
        assertTrue(runner.run("VPN 401", Duration.ofSeconds(2), () -> false, events::add).contains("[C1]"));
        assertEquals(1, calls.get()); assertEquals(3, checks.get());
        assertEquals(List.of("TOOL_REQUESTED","TOOL_STARTED","TOOL_COMPLETED"), events.stream().map(AgentExecutionEvent::type).toList());
    }
    @Test void forgedToolIsDeniedBeforeRemoteExecution() throws Exception {
        AtomicInteger calls=new AtomicInteger(), checks=new AtomicInteger();
        var runner=runner(calls,checks,true,false);
        assertThrows(IllegalStateException.class,()->runner.run("run shell",Duration.ofSeconds(2),()->false,e->{}));
        assertEquals(0,calls.get());
    }
    @Test void sourceChangedAfterGenerationNeverPublishesAnswer() throws Exception {
        var runner=runner(new AtomicInteger(),new AtomicInteger(),false,true);
        assertThrows(IllegalStateException.class,()->runner.run("VPN401",Duration.ofSeconds(2),()->false,e->{}));
    }
    @Test void cancellationNeverStartsDiscoveryOrModel() throws Exception {
        var calls=new AtomicInteger();
        assertThrows(java.util.concurrent.CancellationException.class,()->runner(calls,new AtomicInteger(),false,false)
                .run("VPN401",Duration.ofSeconds(1),()->true,e->{}));
        assertEquals(0,calls.get());
    }
    @Test void invalidArgumentsNeverReachRemoteOrPublicEvents() throws Exception {
        AtomicInteger calls=new AtomicInteger();var events=new ArrayList<AgentExecutionEvent>();
        ResearchRunner.Gateway gateway=new ResearchRunner.Gateway(){
            public List<ResearchRunner.Tool> discover(Duration d,java.util.function.BooleanSupplier c){return List.of(new ResearchRunner.Tool("knowledge__knowledge_lookup_v1","lookup",com.zxl.zxlaiagent.knowledge.mcp.KnowledgeLookupTool.SCHEMA));}
            public String call(String n,String a,Duration d,java.util.function.BooleanSupplier c){calls.incrementAndGet();return "{}";}
        };
        ResearchRunner.Model model=new ResearchRunner.Model(){
            public ResearchRunner.Selection select(String q,List<ResearchRunner.Tool> t,Duration d,java.util.function.BooleanSupplier c){return new ResearchRunner.Selection("1",t.get(0).name(),"{\"query\":\"VPN\",\"tenantId\":\"secret\"}");}
            public String answer(String q,List<com.fasterxml.jackson.databind.JsonNode> e,Duration d,java.util.function.BooleanSupplier c){throw new AssertionError();}
        };
        assertThrows(RuntimeException.class,()->new ResearchRunner(gateway,model,(h,d,c)->h).run("VPN401",Duration.ofSeconds(2),()->false,events::add));
        assertEquals(0,calls.get());assertTrue(events.isEmpty());
    }
    private ResearchRunner runner(AtomicInteger calls,AtomicInteger checks,boolean forged,boolean changed) throws Exception {
        var result=json.readTree("{\"success\":true,\"code\":\"OK\",\"message\":\"done\",\"data\":{\"hits\":[{\"documentId\":\"vpn\",\"chunkId\":\"c\",\"text\":\"通过SSO更新凭据\"}]}}");
        ResearchRunner.Gateway gateway=new ResearchRunner.Gateway(){
            public List<ResearchRunner.Tool> discover(Duration d,java.util.function.BooleanSupplier c){return List.of(new ResearchRunner.Tool("knowledge__knowledge_lookup_v1","lookup",com.zxl.zxlaiagent.knowledge.mcp.KnowledgeLookupTool.SCHEMA));}
            public String call(String n,String a,Duration d,java.util.function.BooleanSupplier c){calls.incrementAndGet();return result.toString();}
        };
        ResearchRunner.Model model=new ResearchRunner.Model(){
            public ResearchRunner.Selection select(String q,List<ResearchRunner.Tool> t,Duration d,java.util.function.BooleanSupplier c){return new ResearchRunner.Selection("call1",forged?"terminal":"knowledge__knowledge_lookup_v1","{\"query\":\"VPN401\"}");}
            public String answer(String q,List<com.fasterxml.jackson.databind.JsonNode> es,Duration d,java.util.function.BooleanSupplier c){return "通过SSO更新凭据 [C1]";}
        };
        return new ResearchRunner(gateway,model,(hits,d,c)->{if(checks.incrementAndGet()>1&&changed)throw new IllegalStateException("EVIDENCE_CHANGED");return hits;});
    }
}
