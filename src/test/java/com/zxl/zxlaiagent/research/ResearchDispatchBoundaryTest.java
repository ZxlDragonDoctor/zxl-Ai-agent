package com.zxl.zxlaiagent.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zxl.zxlaiagent.knowledge.mcp.KnowledgeLookupTool;
import com.zxl.zxlaiagent.tools.model.ToolResult;
import com.zxl.zxlaiagent.tools.support.ToolResults;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class ResearchDispatchBoundaryTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final String NAME="knowledge__knowledge_lookup_v1";
    private static List<ResearchRunner.Tool> tools(){return List.of(new ResearchRunner.Tool(NAME,"Read current evidence",KnowledgeLookupTool.SCHEMA));}

    @Test void queuedAnswerRevalidatesRevokedEvidenceBeforeAnyExternalModelDispatch() throws Exception {
        var pool=Executors.newFixedThreadPool(3);
        var thirdToolReached=new CountDownLatch(1);var allowThirdTool=new CountDownLatch(1);
        var blockedAnswers=new CountDownLatch(2);var releaseAnswers=new CountDownLatch(1);
        var thirdVerified=new CountDownLatch(1);var revoked=new AtomicBoolean();var thirdAnswers=new AtomicInteger();
        ResearchRunner.Gateway gateway=new ResearchRunner.Gateway(){
            public List<ResearchRunner.Tool> discover(Duration d,BooleanSupplier c){return tools();}
            public String call(String n,String a,Duration d,BooleanSupplier c){
                try {
                    String query=JSON.readTree(a).path("query").asText();
                    if(query.equals("third")){thirdToolReached.countDown();await(allowThirdTool);}
                    return result(List.of(Map.of("documentId",query,"chunkId","chunk-"+query,"text","current source")));
                }catch(Exception e){throw new IllegalStateException(e);}
            }
        };
        ResearchRunner.Model model=new ResearchRunner.Model(){
            public ResearchRunner.Selection select(String q,List<ResearchRunner.Tool> t,Duration d,BooleanSupplier c){return new ResearchRunner.Selection("call-"+q,NAME,ToolResults.json(Map.of("query",q)));}
            public String answer(String q,List<JsonNode> hits,Duration d,BooleanSupplier c){
                if(q.equals("third"))thirdAnswers.incrementAndGet();
                else {blockedAnswers.countDown();await(releaseAnswers);}
                return "Current source [C1]";
            }
        };
        var runner=new ResearchRunner(gateway,model,(hits,d,c)->{
            if(hits.get(0).path("documentId").asText().equals("third")){
                if(revoked.get())throw new IllegalStateException("EVIDENCE_REVOKED");
                thirdVerified.countDown();
            }
            return hits;
        });
        List<Future<?>> all=new ArrayList<>();
        try {
            // Complete third selection first: selection and synthesis share the same two-worker pool.
            Future<?> third=pool.submit(()->runner.run("third",Duration.ofSeconds(15),()->false,e->{}));all.add(third);
            assertTrue(thirdToolReached.await(5,TimeUnit.SECONDS));
            all.add(pool.submit(()->runner.run("block-one",Duration.ofSeconds(15),()->false,e->{})));
            all.add(pool.submit(()->runner.run("block-two",Duration.ofSeconds(15),()->false,e->{})));
            assertTrue(blockedAnswers.await(5,TimeUnit.SECONDS));
            allowThirdTool.countDown();
            assertTrue(thirdVerified.await(5,TimeUnit.SECONDS));
            revoked.set(true);releaseAnswers.countDown();
            assertThrows(ExecutionException.class,()->third.get(5,TimeUnit.SECONDS));
            assertEquals(0,thirdAnswers.get(),"Final rejection is too late: revoked source must never reach the external model");
        } finally {
            allowThirdTool.countDown();releaseAnswers.countDown();
            for(Future<?> f:all)try{f.get(5,TimeUnit.SECONDS);}catch(Exception ignored){f.cancel(true);}
            pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));
        }
    }

    @Test void individuallyValidSourcesCannotExceedCombinedModelInputByteBudget() {
        List<Map<String,Object>> hits=new ArrayList<>();
        for(int i=0;i<5;i++)hits.add(Map.of("documentId","doc"+i,"chunkId","chunk"+i,"text","中".repeat(4000)));
        AtomicInteger answerCalls=new AtomicInteger();
        ResearchRunner runner=new ResearchRunner(gateway(result(hits)),model(answerCalls),(h,d,c)->h);
        assertThrows(RuntimeException.class,()->runner.run("VPN401",Duration.ofSeconds(2),()->false,e->{}));
        assertEquals(0,answerCalls.get(),"The total serialized model payload must be bounded even when each source is individually valid");
    }

    @Test void directGatewayResponseOverAbsoluteByteLimitIsRejectedBeforePublicEventOrModel() {
        String response=result(List.of(Map.of("documentId","doc","chunkId","c","text","x","untrustedExtra","中".repeat(50000))));
        AtomicInteger answerCalls=new AtomicInteger();var events=new ArrayList<com.zxl.zxlaiagent.agent.model.AgentExecutionEvent>();
        ResearchRunner runner=new ResearchRunner(gateway(response),model(answerCalls),(h,d,c)->h);
        assertThrows(RuntimeException.class,()->runner.run("VPN401",Duration.ofSeconds(2),()->false,events::add));
        assertEquals(0,answerCalls.get());
        assertFalse(events.stream().anyMatch(e->"TOOL_COMPLETED".equals(e.type())),"An unbounded gateway result must not be published as successful public content");
    }

    private static ResearchRunner.Gateway gateway(String response){return new ResearchRunner.Gateway(){
        public List<ResearchRunner.Tool> discover(Duration d,BooleanSupplier c){return tools();}
        public String call(String n,String a,Duration d,BooleanSupplier c){return response;}
    };}
    private static ResearchRunner.Model model(AtomicInteger calls){return new ResearchRunner.Model(){
        public ResearchRunner.Selection select(String q,List<ResearchRunner.Tool> t,Duration d,BooleanSupplier c){return new ResearchRunner.Selection("call",NAME,"{\"query\":\"VPN401\"}");}
        public String answer(String q,List<JsonNode> h,Duration d,BooleanSupplier c){calls.incrementAndGet();return "Answer [C1]";}
    };}
    private static String result(Object hits){return ToolResults.json(ToolResult.ok("done",Map.of("hits",hits)));}
    private static void await(CountDownLatch latch){try{if(!latch.await(10,TimeUnit.SECONDS))throw new IllegalStateException("latch timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new CancellationException();}}
}
