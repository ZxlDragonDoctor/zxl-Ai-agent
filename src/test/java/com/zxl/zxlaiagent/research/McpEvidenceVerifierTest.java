package com.zxl.zxlaiagent.research;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import com.zxl.zxlaiagent.skills.*;
import com.zxl.zxlaiagent.agent.model.AgentExecutionEvent;
import static org.junit.jupiter.api.Assertions.*;

class McpEvidenceVerifierTest {
    final ObjectMapper json=new ObjectMapper();
    @Test void wrappedTransportTimeoutAndCancellationUseTypesWithoutLeakingDetails(){
        var timeout=McpEvidenceVerifier.classifyFailure(new ExecutionException(new java.net.http.HttpTimeoutException("private URI and body")));
        assertEquals("RESEARCH_DEADLINE",timeout.getMessage());assertNull(timeout.getCause());
        var cancellation=McpEvidenceVerifier.classifyFailure(new ExecutionException(new CancellationException("private body")));
        assertInstanceOf(CancellationException.class,cancellation);assertEquals("RESEARCH_CANCELLED",cancellation.getMessage());assertNull(cancellation.getCause());
        for(String message:List.of("RESEARCH_DEADLINE","RESEARCH_CANCELLED","http://127.0.0.1/private?secret=body")){
            var unknown=McpEvidenceVerifier.classifyFailure(new ExecutionException(new IllegalStateException(message)));
            assertEquals("EVIDENCE_GATEWAY_FAILED",unknown.getMessage());assertNull(unknown.getCause());
        }
    }
    @Test void wrappedInterruptionRetainsThreadFlagAndSafeCancellation(){
        try {
            var failure=McpEvidenceVerifier.classifyFailure(new ExecutionException(new InterruptedException("private body")));
            assertInstanceOf(CancellationException.class,failure);assertEquals("RESEARCH_CANCELLED",failure.getMessage());assertTrue(Thread.currentThread().isInterrupted());
        }finally{Thread.interrupted();}
    }
    @Test void slowEvidenceLookupRetainsSharedDeadlineWithoutLateDraft() throws Exception {
        assertDelayedEvidenceTerminal(false,SkillRunner.Status.TIMED_OUT,"SKILL_DEADLINE");
    }
    @Test void cancellationWhileWaitingForEvidenceRetainsCancelledTerminal() throws Exception {
        assertDelayedEvidenceTerminal(true,SkillRunner.Status.CANCELLED,"SKILL_CANCELLED");
    }
    private void assertDelayedEvidenceTerminal(boolean cancel,SkillRunner.Status expected,String code) throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var finished=new CountDownLatch(1);
        var stopped=new AtomicBoolean();var answers=new AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/ai/learning/knowledge/documents/vpn",exchange->{
            entered.countDown();
            try {release.await(5,TimeUnit.SECONDS);byte[] body="{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);}
            catch(InterruptedException e){Thread.currentThread().interrupt();}
            catch(java.io.IOException ignored){/* client already cancelled its HTTP request */}
            finally{exchange.close();finished.countDown();}
        });server.start();
        var executor=Executors.newSingleThreadExecutor();
        var events=new CopyOnWriteArrayList<AgentExecutionEvent>();
        try {
            var verifier=new McpEvidenceVerifier(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/ai/learning/knowledge/documents/"));
            var gateway=new ResearchRunner.Gateway(){
                public List<ResearchRunner.Tool> discover(Duration d,java.util.function.BooleanSupplier c){return List.of(new ResearchRunner.Tool("knowledge__knowledge_lookup_v1","lookup",com.zxl.zxlaiagent.knowledge.mcp.KnowledgeLookupTool.SCHEMA));}
                public String call(String n,String a,Duration d,java.util.function.BooleanSupplier c){return "{\"success\":true,\"code\":\"OK\",\"message\":\"found\",\"data\":{\"hits\":[{\"documentId\":\"vpn\"}]}}";}
            };
            var model=new ResearchRunner.Model(){
                public ResearchRunner.Selection select(String q,List<ResearchRunner.Tool> t,Duration d,java.util.function.BooleanSupplier c){return new ResearchRunner.Selection("one",t.get(0).name(),"{\"query\":\"VPN 401\"}");}
                public String answer(String q,List<JsonNode> h,Duration d,java.util.function.BooleanSupplier c){answers.incrementAndGet();return "late [C1]";}
            };
            var runner=new SkillRunner(new SkillRegistry(SkillCatalogue.builtIns()),new SkillExecutionPolicy(Set.of("knowledge__knowledge_lookup_v1")),gateway,model,verifier);
            long start=System.nanoTime();
            var future=executor.submit(()->runner.run("it-ticket-analysis","v1","VPN 401",Duration.ofMillis(cancel?3000:900),SkillExecutionPolicy.demo(),stopped::get,events::add));
            assertTrue(entered.await(2,TimeUnit.SECONDS),"request must reach the real HTTP server");
            if(cancel)stopped.set(true);
            var result=future.get(2,TimeUnit.SECONDS);
            assertEquals(expected,result.status());assertEquals(code,result.code());assertNull(result.draft());assertEquals(0,answers.get());
            assertTrue(Duration.ofNanos(System.nanoTime()-start).toMillis()<2000,"lookup must not reset the shared deadline");
            int count=events.size();release.countDown();assertTrue(finished.await(1,TimeUnit.SECONDS));
            assertEquals(count,events.size());assertEquals("SKILL_TERMINAL",events.get(count-1).type());
            assertEquals(1,events.stream().filter(e->e.type().equals("SKILL_TERMINAL")).count());
        }finally{release.countDown();server.stop(0);executor.shutdownNow();}
    }
    @Test void trustedDocumentMustMatchRevisionGenerationChunkRawTextAndLines()throws Exception{
        var detail=json.readTree("{\"document\":{\"documentId\":\"vpn\",\"status\":\"ACTIVE\",\"activeRevision\":\"r\",\"version\":2},\"rawText\":\"SSO\",\"chunks\":[{\"chunkId\":\"c\",\"text\":\"SSO\",\"startUtf16\":0,\"endUtf16\":3}]}");
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/ai/learning/knowledge/documents/vpn",ex->{byte[] body=detail.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);ex.sendResponseHeaders(200,body.length);ex.getResponseBody().write(body);ex.close();});server.start();
        try{
            var verifier=new McpEvidenceVerifier(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/ai/learning/knowledge/documents/"));
            var hit=json.readTree("{\"documentId\":\"vpn\",\"revisionId\":\"r\",\"generation\":2,\"chunkId\":\"c\",\"text\":\"SSO\",\"startUtf16\":0,\"endUtf16\":3,\"startLine\":1,\"endLine\":1}");
            assertEquals(List.of(hit),verifier.verify(List.of(hit),Duration.ofSeconds(2),()->false));
            for(String field:List.of("generation","startUtf16","startLine")){
                var forged=hit.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)forged).put(field,99);
                assertThrows(IllegalStateException.class,()->verifier.verify(List.of(forged),Duration.ofSeconds(2),()->false));
            }
            ((com.fasterxml.jackson.databind.node.ObjectNode)detail.path("document")).put("status","DELETED");
            assertThrows(IllegalStateException.class,()->verifier.verify(List.of(hit),Duration.ofSeconds(2),()->false));
        }finally{server.stop(0);}
    }
    @Test void pathsAndNonLoopbackTargetsAreRejected(){
        for(String url:List.of("https://127.0.0.1:18132/ai/learning/knowledge/documents/","http://example.com:18132/ai/learning/knowledge/documents/","http://127.0.0.1:18132/other/"))assertThrows(IllegalArgumentException.class,()->new McpEvidenceVerifier(URI.create(url)));
    }
    @Test void providerRegistersInUnifiedRegistryAndPropagatesActualToolEvents(){
        var calls=new AtomicInteger();
        ResearchRunner.Model model=new ResearchRunner.Model(){
            public ResearchRunner.Selection select(String q,List<ResearchRunner.Tool> t,Duration d,java.util.function.BooleanSupplier c){calls.incrementAndGet();return new ResearchRunner.Selection("one",t.get(0).name(),"{\"query\":\"VPN401\"}");}
            public String answer(String q,List<JsonNode> h,Duration d,java.util.function.BooleanSupplier c){throw new AssertionError("empty must not generate");}
        };
        var gateway=new ResearchRunner.Gateway(){
            public List<ResearchRunner.Tool> discover(Duration d,java.util.function.BooleanSupplier c){return List.of(new ResearchRunner.Tool("knowledge__knowledge_lookup_v1","lookup",com.zxl.zxlaiagent.knowledge.mcp.KnowledgeLookupTool.SCHEMA));}
            public String call(String n,String a,Duration d,java.util.function.BooleanSupplier c){return "{\"success\":true,\"code\":\"OK\",\"message\":\"empty\",\"data\":{\"hits\":[]}}";}
        };
        var provider=new com.zxl.zxlaiagent.conversation.agent.McpResearchProvider(new ResearchRunner(gateway,model,(h,d,c)->h));
        assertEquals("mcp-research",provider.definition().id());
        var agent=provider.create();var events=new ArrayList<com.zxl.zxlaiagent.agent.model.AgentExecutionEvent>();agent.setEventObserver(events::add);
        assertTrue(agent.run("VPN401").contains("没有找到"));assertEquals(1,calls.get());
        assertTrue(events.stream().anyMatch(e->e.type().equals("TOOL_COMPLETED")));assertTrue(events.stream().anyMatch(e->e.type().equals("ANSWER_DELTA")));
    }
}
