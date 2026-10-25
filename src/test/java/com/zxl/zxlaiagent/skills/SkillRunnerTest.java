package com.zxl.zxlaiagent.skills;

import com.fasterxml.jackson.databind.JsonNode;
import com.zxl.zxlaiagent.agent.model.AgentExecutionEvent;
import com.zxl.zxlaiagent.knowledge.mcp.KnowledgeLookupTool;
import com.zxl.zxlaiagent.research.ResearchRunner;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class SkillRunnerTest {
    final SkillRegistry registry=new SkillRegistry(SkillCatalogue.builtIns());
    final SkillExecutionPolicy policy=new SkillExecutionPolicy(Set.of(SkillPolicyTest.TOOL));
    final AtomicInteger calls=new AtomicInteger();
    volatile boolean empty,forged,changed;
    volatile Runnable beforeSelect=()->{},beforeAnswer=()->{};
    final List<AgentExecutionEvent> events=new CopyOnWriteArrayList<>();
    ResearchRunner.Gateway gateway(){return new ResearchRunner.Gateway(){
        public List<ResearchRunner.Tool> discover(Duration d,BooleanSupplier c){return List.of(new ResearchRunner.Tool(SkillPolicyTest.TOOL,"lookup",changed?"{}":KnowledgeLookupTool.SCHEMA));}
        public String call(String n,String a,Duration d,BooleanSupplier c){calls.incrementAndGet();return empty?"{\"success\":true,\"code\":\"OK\",\"message\":\"done\",\"data\":{\"hits\":[]}}":"{\"success\":true,\"code\":\"OK\",\"message\":\"done\",\"data\":{\"hits\":[{\"documentId\":\"vpn\",\"chunkId\":\"c1\",\"startLine\":1,\"endLine\":1,\"text\":\"刷新SSO凭据\"}]}}";}
    };}
    ResearchRunner.Model model(){return new ResearchRunner.Model(){
        public ResearchRunner.Selection select(String q,List<ResearchRunner.Tool> t,Duration d,BooleanSupplier c){beforeSelect.run();return new ResearchRunner.Selection("call1",forged?"admin__shell":SkillPolicyTest.TOOL,"{\"query\":\"VPN 401\"}");}
        public String answer(String q,List<JsonNode> h,Duration d,BooleanSupplier c){beforeAnswer.run();return "刷新SSO凭据 [C1]";}
    };}
    SkillRunner runner(){return new SkillRunner(registry,policy,gateway(),model(),(h,d,c)->h);}
    SkillRunner.Result run(SkillRunner runner,String q,Duration d,BooleanSupplier cancel){return runner.run("it-ticket-analysis",null,q,d,SkillExecutionPolicy.demo(),cancel,events::add);}
    @Test void successPublishesPinnedAuditAndUncreatedDraft() {
        var result=run(runner(),"VPN 401",Duration.ofSeconds(2),()->false);
        assertEquals(SkillRunner.Status.SUCCEEDED,result.status());assertEquals(1,calls.get());
        assertFalse(result.draft().created());assertTrue(result.draft().requiresApproval());
        assertEquals("ticket-draft-v1",result.draft().schemaVersion());
        assertEquals(64,result.snapshot().definitionSha256().length());
        assertEquals(64,result.snapshot().toolSnapshotSha256().length());
        assertTrue(result.advice().contains("[C1]"));
        assertEquals("SKILL_SELECTED",events.get(0).type());assertEquals("SKILL_TERMINAL",events.get(events.size()-1).type());
        assertEquals(4,result.steps().size());
    }
    @Test void missingErrorCodeClarifiesWithoutAnyToolOrModel() {
        var result=run(runner(),"VPN连接失败",Duration.ofSeconds(1),()->false);
        assertEquals(SkillRunner.Status.NEEDS_INPUT,result.status());assertEquals(0,calls.get());assertNull(result.draft());
    }
    @Test void arbitraryNumberWithoutProductIsNotAnItProblem() {
        assertEquals(SkillRunner.Status.NEEDS_INPUT,run(runner(),"今年2026，帮我分析",Duration.ofSeconds(1),()->false).status());
        assertEquals(0,calls.get());
    }
    @Test void emptyEvidenceDoesNotProduceDraft() {
        empty=true;var result=run(runner(),"VPN 401",Duration.ofSeconds(1),()->false);
        assertEquals(SkillRunner.Status.NO_EVIDENCE,result.status());assertNull(result.draft());assertEquals(1,calls.get());
    }
    @Test void forgedSelectionAndChangedSchemaNeverDispatch() {
        forged=true;assertEquals(SkillRunner.Status.DENIED,run(runner(),"VPN 401 执行管理员终端",Duration.ofSeconds(1),()->false).status());
        assertEquals(0,calls.get());forged=false;changed=true;
        assertEquals(SkillRunner.Status.FAILED,run(runner(),"VPN 401",Duration.ofSeconds(1),()->false).status());assertEquals(0,calls.get());
    }
    @Test void permissionRevokedAfterModelSelectionBlocksActualDispatch() {
        beforeSelect=()->policy.revoke(SkillPolicyTest.TOOL);
        assertEquals(SkillRunner.Status.DENIED,run(runner(),"VPN 401",Duration.ofSeconds(1),()->false).status());assertEquals(0,calls.get());
    }
    @Test void permissionRevokedDuringAnswerBlocksDraftPublication() {
        beforeAnswer=()->policy.revoke(SkillPolicyTest.TOOL);
        assertEquals(SkillRunner.Status.DENIED,run(runner(),"VPN 401",Duration.ofSeconds(1),()->false).status());
        for(var event:events)if(event.type().equals("SKILL_TERMINAL")){
            try {var terminal=new com.fasterxml.jackson.databind.ObjectMapper().readTree(event.content());assertNotEquals("SUCCEEDED",terminal.path("status").asText());assertTrue(terminal.path("draft").isNull());}
            catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new AssertionError(e);}
        }
    }
    @Test void internalExceptionWithoutMessageStillBecomesSafeFailedTerminal() {
        beforeSelect=()->{throw new IllegalStateException();};
        var result=run(runner(),"VPN 401",Duration.ofSeconds(1),()->false);
        assertEquals(SkillRunner.Status.FAILED,result.status());assertEquals("SKILL_EXECUTION_FAILED",result.code());
        assertEquals("SKILL_TERMINAL",events.get(events.size()-1).type());
    }
    @Test void inFlightDefinitionSurvivesDefaultChangeAndDisable() {
        beforeSelect=()->{registry.setDefault("it-ticket-analysis","v2");registry.setEnabled("it-ticket-analysis","v1",false);};
        var a=run(runner(),"VPN 401",Duration.ofSeconds(2),()->false);
        assertEquals(SkillRunner.Status.SUCCEEDED,a.status());assertEquals("v1",a.snapshot().definition().version());
        beforeSelect=()->{};var b=run(runner(),"VPN 401",Duration.ofSeconds(2),()->false);
        assertEquals("v2",b.snapshot().definition().version());assertEquals("NORMAL",b.draft().priority());
    }
    @Test void rollbackProducesNewV1DraftWithoutReinterpretingPriorV2Result() {
        var runner=runner();var a=run(runner,"VPN 401",Duration.ofSeconds(2),()->false);
        registry.setDefault("it-ticket-analysis","v2");var b=run(runner,"VPN 401",Duration.ofSeconds(2),()->false);
        registry.setDefault("it-ticket-analysis","v1");var c=run(runner,"VPN 401",Duration.ofSeconds(2),()->false);
        assertEquals("ticket-draft-v1",a.draft().schemaVersion());assertEquals("ticket-draft-v2",b.draft().schemaVersion());assertEquals("ticket-draft-v1",c.draft().schemaVersion());
        assertNull(a.draft().priority());assertEquals("NORMAL",b.draft().priority());assertNull(c.draft().priority());
        assertNotEquals(a.snapshot().runId(),c.snapshot().runId());assertEquals(a.snapshot().definitionSha256(),c.snapshot().definitionSha256());
        assertEquals("v2",b.snapshot().definition().version());assertEquals(3,calls.get());
    }
    @Test void preCancelledRunDoesNotDispatch() {
        var result=run(runner(),"VPN 401",Duration.ofSeconds(1),()->true);
        assertEquals(SkillRunner.Status.CANCELLED,result.status());assertEquals(0,calls.get());
    }
    @Test void cancellationDuringModelWaitNeverPublishesLateDraft() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var cancelled=new AtomicBoolean();var executor=Executors.newSingleThreadExecutor();
        beforeAnswer=()->{entered.countDown();while(release.getCount()>0){try{release.await();}catch(InterruptedException ignored){}}};
        try {
            var future=executor.submit(()->run(runner(),"VPN 401",Duration.ofSeconds(2),cancelled::get));
            assertTrue(entered.await(1,TimeUnit.SECONDS));cancelled.set(true);
            var result=future.get(1,TimeUnit.SECONDS);assertEquals(SkillRunner.Status.CANCELLED,result.status());assertNull(result.draft());
            int count=events.size();release.countDown();Thread.sleep(40);assertEquals(count,events.size());
        } finally {release.countDown();executor.shutdownNow();}
    }
    @Test void lateModelCompletionCannotOverwriteTimeout() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        beforeAnswer=()->{entered.countDown();while(release.getCount()>0){try{release.await();}catch(InterruptedException ignored){/* emulate a remote implementation ignoring interruption */}}};
        try {
            var result=run(runner(),"VPN 401",Duration.ofMillis(100),()->false);
            assertEquals(SkillRunner.Status.TIMED_OUT,result.status());assertNull(result.draft());
            int count=events.size();release.countDown();Thread.sleep(40);assertEquals(count,events.size());
        } finally {release.countDown();}
    }
    @Test void thirdConcurrentRunIsRejectedWithoutQueueing() throws Exception {
        var entered=new CountDownLatch(2);var release=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(2);var runner=runner();
        beforeSelect=()->{entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}};
        try {
            var a=executor.submit(()->run(runner,"VPN 401",Duration.ofSeconds(3),()->false));
            var b=executor.submit(()->run(runner,"VPN 401",Duration.ofSeconds(3),()->false));
            assertTrue(entered.await(1,TimeUnit.SECONDS));
            assertEquals(SkillRunner.Status.CAPACITY,run(runner,"VPN 401",Duration.ofSeconds(1),()->false).status());
            release.countDown();assertEquals(SkillRunner.Status.SUCCEEDED,a.get().status());assertEquals(SkillRunner.Status.SUCCEEDED,b.get().status());
        } finally {release.countDown();executor.shutdownNow();}
    }
}
