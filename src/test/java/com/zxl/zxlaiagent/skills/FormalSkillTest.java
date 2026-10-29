package com.zxl.zxlaiagent.skills;

import com.zxl.zxlaiagent.agent.model.AgentExecutionEvent;
import com.zxl.zxlaiagent.research.*;
import com.zxl.zxlaiagent.security.*;
import com.zxl.zxlaiagent.knowledge.mcp.FormalKnowledgeMcpProtocol;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class FormalSkillTest {
    private final ActorRef actor = new ActorRef("subject", "tenant", "session", 1);
    private final ScopedResearchFactory factory = mock(ScopedResearchFactory.class);
    private final AuthorizationService authorization = mock(AuthorizationService.class);
    private final ResearchRunner research = mock(ResearchRunner.class);
    private ScopedSkillRunner runner() {
        when(authorization.gateAll(eq(actor), anyList(), any(), anyString(), any()))
                .thenAnswer(call -> ((Supplier<?>) call.getArgument(4)).get());
        return new ScopedSkillRunner(new SkillRegistry(SkillCatalogue.formalBuiltIns()), factory, authorization);
    }
    private SkillRunner.Result run(ScopedSkillRunner runner, String version, String question, List<AgentExecutionEvent> events) {
        return runner.runWithAuthorizedPublisher(actor, "conversation", "run", "it-ticket-analysis", version,
                question, Duration.ofSeconds(30), () -> false, events::add);
    }
    @Test void reviewedFormalVersionsPinV2WithoutRewritingLegacyDefinitions() {
        assertEquals(List.of("v1", "v2"), SkillCatalogue.builtIns().stream().map(SkillDefinition::version).toList());
        var definitions = SkillCatalogue.formalBuiltIns();
        assertEquals(List.of("v3", "v4"), definitions.stream().map(SkillDefinition::version).toList());
        for (var definition : definitions) {
            var tool = definition.tools().get(0);
            assertEquals(FormalKnowledgeMcpProtocol.EXPOSED_NAME, tool.exposedName());
            assertEquals("v2", tool.schemaVersion());
            assertEquals(SkillDefinition.digest(FormalKnowledgeMcpProtocol.modelDefinition().inputSchema()), tool.schemaSha256());
            assertTrue(tool.readOnly());
        }
        assertEquals("ticket-draft-v1", definitions.get(0).outputSchemaVersion());
        assertEquals("ticket-draft-v2", definitions.get(1).outputSchemaVersion());
        assertNotEquals(definitions.get(0).sha256(), definitions.get(1).sha256());
    }
    @Test void clarifyDoesNotStartResearchAndDoesNotPublishIdentityOrCredentials() {
        var events = new ArrayList<AgentExecutionEvent>();
        var result = run(runner(), "v3", "VPN连接失败", events);
        assertEquals(SkillRunner.Status.NEEDS_INPUT, result.status());
        assertNull(result.draft()); verifyNoInteractions(factory, research);
        assertTrue(events.stream().anyMatch(e -> e.type().equals("SKILL_SELECTED")));
        assertFalse(events.toString().contains("session"));
        assertFalse(events.toString().contains("subject"));
    }
    @Test void researchGetsTrustedActorRunAndConversationAndDraftIsNeverCreated() {
        var runner = runner(); var events = new ArrayList<AgentExecutionEvent>();
        when(factory.create(eq(actor), eq("run"), anyList())).thenAnswer(call -> {
            List<AuthorizationService.Permission> required = call.getArgument(2);
            assertTrue(required.contains(new AuthorizationService.Permission("conversation.readOwn", new ResourceRef("conversation", "conversation"))));
            assertTrue(required.contains(new AuthorizationService.Permission("run.readOwn", new ResourceRef("run", "run"))));
            assertTrue(required.contains(new AuthorizationService.Permission("skill.execute", null)));
            return research;
        });
        when(research.runWithAuthorizedPublisher(eq("VPN-401如何处理"), any(), any(), any())).thenReturn("重新登录 [C1]");
        var result = run(runner, "v4", "VPN-401如何处理", events);
        assertEquals(SkillRunner.Status.SUCCEEDED, result.status());
        assertFalse(result.draft().created()); assertTrue(result.draft().requiresApproval());
        assertEquals("NORMAL", result.draft().priority());
        verify(research).runWithAuthorizedPublisher(anyString(), any(), any(), any());
        verify(research, never()).run(anyString(), any(), any(), any());
        assertEquals(4, result.steps().size());
    }
    @Test void unavailableLegacyOrUnknownVersionNeverStartsResearch() {
        var runner = runner();
        for (String version : List.of("v1", "v2", "v99")) {
            assertThrows(IllegalArgumentException.class, () -> run(runner, version, "VPN-401", new ArrayList<>()));
        }
        verifyNoInteractions(factory, research);
    }
    @Test void revokedActorNeverStartsResearchOrPublishesDraft() {
        var runner = runner();
        when(authorization.gateAll(eq(actor), anyList(), any(), anyString(), any()))
                .thenThrow(new SecurityFailure("ACTION_DENIED", 403));
        assertThrows(SecurityFailure.class, () -> run(runner, "v3", "VPN-401", new ArrayList<>()));
        verifyNoInteractions(factory, research);
    }
    @Test void revocationAfterResearchDiscardsAdviceAndDraft() {
        var runner = runner(); var events = new ArrayList<AgentExecutionEvent>();
        when(factory.create(eq(actor), eq("run"), anyList())).thenReturn(research);
        when(research.runWithAuthorizedPublisher(anyString(), any(), any(), any())).thenAnswer(call -> {
            when(authorization.gateAll(eq(actor), anyList(), any(), anyString(), any()))
                    .thenThrow(new SecurityFailure("ACTION_DENIED", 403));
            return "sensitive result [C1]";
        });
        assertThrows(SecurityFailure.class, () -> run(runner, "v4", "VPN-401", events));
        assertFalse(events.toString().contains("sensitive result"));
        assertFalse(events.stream().anyMatch(e -> e.type().equals("SKILL_TERMINAL")));
    }
    @Test void emptyCurrentEvidenceNeverProducesDraft() {
        var runner = runner(); var events = new ArrayList<AgentExecutionEvent>();
        var gateway = mock(ResearchRunner.Gateway.class); var model = mock(ResearchRunner.Model.class);
        var verifier = mock(ResearchRunner.Verifier.class);
        when(gateway.discover(any(), any())).thenReturn(List.of(new ResearchRunner.Tool(
                FormalKnowledgeMcpProtocol.EXPOSED_NAME, "read", FormalKnowledgeMcpProtocol.MODEL_SCHEMA)));
        when(model.select(anyString(), anyList(), any(), any())).thenReturn(new ResearchRunner.Selection(
                "call", FormalKnowledgeMcpProtocol.EXPOSED_NAME, "{\"query\":\"VPN-401\"}"));
        String emptyEvidence = com.zxl.zxlaiagent.tools.support.ToolResults.json(
                com.zxl.zxlaiagent.tools.model.ToolResult.ok("当前范围没有匹配资料", Map.of("hits", List.of())));
        assertTrue(com.zxl.zxlaiagent.tools.support.ToolResults.read(emptyEvidence).path("data").path("hits").isEmpty());
        when(gateway.call(anyString(), anyString(), any(), any())).thenReturn(emptyEvidence);
        when(factory.create(eq(actor), eq("run"), anyList())).thenReturn(ResearchRunner.authorized(
                gateway, model, verifier, com.zxl.zxlaiagent.security.execution.OperationBoundary.unrestricted()));
        var result = run(runner, "v3", "VPN-401", events);
        assertEquals(SkillRunner.Status.NO_EVIDENCE, result.status()); assertNull(result.draft());
        verify(model, never()).answer(anyString(), anyList(), any(), any()); verifyNoInteractions(verifier);
    }
    @Test void preCancelledRunStartsNoResearchAndExpiredBudgetPublishesNoDraft() {
        var runner = runner(); var events = new ArrayList<AgentExecutionEvent>();
        assertThrows(java.util.concurrent.CancellationException.class, () -> runner.runWithAuthorizedPublisher(actor, "conversation", "run",
                "it-ticket-analysis", "v3", "VPN-401", Duration.ofSeconds(30), () -> true, events::add));
        assertThrows(IllegalStateException.class, () -> runner.runWithAuthorizedPublisher(actor, "conversation", "run",
                "it-ticket-analysis", "v3", "VPN-401", Duration.ofNanos(1), () -> false, events::add));
        assertTrue(events.isEmpty()); verifyNoInteractions(factory, research);
    }
    @Test void baseAgentPublisherIsNeverInvokedInsideAnotherAuthorizationGate() {
        var runner = runner(); var inside = new java.util.concurrent.atomic.AtomicBoolean();
        when(authorization.gateAll(eq(actor), anyList(), any(), anyString(), any())).thenAnswer(call -> {
            assertFalse(inside.getAndSet(true));
            try { return ((Supplier<?>) call.getArgument(4)).get(); }
            finally { inside.set(false); }
        });
        var result = runner.runWithAuthorizedPublisher(actor, "conversation", "run", "it-ticket-analysis", "v3", "VPN连接失败",
                Duration.ofSeconds(30), () -> false, event -> assertFalse(inside.get()));
        assertEquals(SkillRunner.Status.NEEDS_INPUT, result.status());
    }
    @Test void cancellationDuringLookupProducesNeutralCancelledTerminalWithoutDraft() {
        var runner=runner(); var events=new ArrayList<AgentExecutionEvent>();
        var cancelled=new java.util.concurrent.atomic.AtomicBoolean();
        when(factory.create(eq(actor),eq("run"),anyList())).thenReturn(research);
        when(research.runWithAuthorizedPublisher(anyString(),any(),any(),any())).thenAnswer(call->{
            cancelled.set(true);throw new java.util.concurrent.CancellationException("RESEARCH_CANCELLED");
        });
        var result=runner.runWithAuthorizedPublisher(actor,"conversation","run","it-ticket-analysis","v3","VPN-401",
                Duration.ofSeconds(30),cancelled::get,events::add);
        assertEquals(SkillRunner.Status.CANCELLED,result.status());assertNull(result.draft());
        assertEquals("CANCELLED",result.steps().get(result.steps().size()-1).status());
        assertTrue(events.stream().anyMatch(e->e.type().equals("SKILL_TERMINAL")&&e.content().contains("CANCELLED")));
        assertFalse(events.stream().anyMatch(e->e.type().equals("SKILL_STEP")&&e.content().contains("DRAFT")));
    }
    @Test void deadlineDuringLookupProducesNeutralTimedOutTerminalWithoutDraft() {
        var runner=runner(); var events=new ArrayList<AgentExecutionEvent>();
        when(factory.create(eq(actor),eq("run"),anyList())).thenReturn(research);
        when(research.runWithAuthorizedPublisher(anyString(),any(),any(),any())).thenAnswer(call->{
            Thread.sleep(1100);return "sensitive-late-answer [C1]";
        });
        var result=runner.runWithAuthorizedPublisher(actor,"conversation","run","it-ticket-analysis","v4","VPN-401",
                Duration.ofSeconds(1),()->false,events::add);
        verify(research).runWithAuthorizedPublisher(anyString(),any(),any(),any());
        assertEquals(SkillRunner.Status.TIMED_OUT,result.status());assertNull(result.draft());
        assertTrue(events.stream().anyMatch(e->e.type().equals("SKILL_TERMINAL")&&e.content().contains("TIMED_OUT")));
        assertFalse(events.toString().contains("sensitive-late-answer"));
    }
    @Test void closedPublisherDoesNotEraseLocalCancellationOutcome() {
        var runner=runner();var cancelled=new java.util.concurrent.atomic.AtomicBoolean();
        when(factory.create(eq(actor),eq("run"),anyList())).thenReturn(research);
        when(research.runWithAuthorizedPublisher(anyString(),any(),any(),any())).thenAnswer(call->{
            cancelled.set(true);throw new java.util.concurrent.CancellationException("RESEARCH_CANCELLED");
        });
        var result=runner.runWithAuthorizedPublisher(actor,"conversation","run","it-ticket-analysis","v3","VPN-401",
                Duration.ofSeconds(30),cancelled::get,event->{if(cancelled.get())throw new java.util.concurrent.CancellationException("Publisher closed");});
        assertEquals(SkillRunner.Status.CANCELLED,result.status());assertNull(result.draft());
    }
    @Test void typedMcpCancellationKeepsCancellationSemantics() {
        var runner=runner();var events=new ArrayList<AgentExecutionEvent>();
        when(factory.create(eq(actor),eq("run"),anyList())).thenReturn(research);
        when(research.runWithAuthorizedPublisher(anyString(),any(),any(),any()))
                .thenThrow(new com.zxl.zxlaiagent.knowledge.mcp.FormalKnowledgeMcpClient.Failure("MCP_CANCELLED"));
        var result=run(runner,"v3","VPN-401",events);
        assertEquals(SkillRunner.Status.CANCELLED,result.status());assertNull(result.draft());
    }
}
