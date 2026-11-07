package com.zxl.zxlaiagent.skills;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zxl.zxlaiagent.agent.ToolCallAgent;
import com.zxl.zxlaiagent.agent.model.AgentExecutionEvent;
import com.zxl.zxlaiagent.conversation.agent.AgentRunOptions;
import com.zxl.zxlaiagent.conversation.agent.ItTicketSkillProvider;
import com.zxl.zxlaiagent.research.ResearchRunner;
import com.zxl.zxlaiagent.research.ScopedResearchFactory;
import com.zxl.zxlaiagent.security.ActorRef;
import com.zxl.zxlaiagent.security.AuthenticationService;
import com.zxl.zxlaiagent.security.AuthorizationService;
import com.zxl.zxlaiagent.security.AuthorizationService.Permission;
import com.zxl.zxlaiagent.security.Phase;
import com.zxl.zxlaiagent.security.ResourceRef;
import com.zxl.zxlaiagent.security.SecurityDatabase;
import com.zxl.zxlaiagent.security.SecurityFailure;
import com.zxl.zxlaiagent.security.execution.ActorBoundary;
import com.zxl.zxlaiagent.security.execution.OperationBoundary;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real PG authority and actual Skill provider publication, with deterministic research responses. */
class FormalSkillRevocationPgTest {
    private static final String QUESTION = "VPN-401 如何处理";
    private static final String ADVICE = "synthetic-restricted-advice [C1]";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final String subject = "skill-revoke-" + UUID.randomUUID();
    private final String tenant = subject + "-tenant";
    private final String conversationId = UUID.randomUUID().toString();
    private final String runId = UUID.randomUUID().toString();
    private final List<AgentExecutionEvent> events = new CopyOnWriteArrayList<>();
    private final ScopedResearchFactory researchFactory = mock(ScopedResearchFactory.class);
    private final ResearchRunner research = mock(ResearchRunner.class);
    private SecurityDatabase database;
    private AuthenticationService authentication;
    private AuthorizationService authorization;
    private ActorRef actor;

    @BeforeEach
    void provision() {
        database = SecurityDatabase.pooledFromEnvironment();
        authentication = new AuthenticationService(database);
        authorization = new AuthorizationService(database);
        authentication.provision(subject, subject, "synthetic-password", tenant, "employee");
        actor = authentication.login(subject, "synthetic-password", tenant).actor().ref();
        authorization.registerResource(actor, new ResourceRef("conversation", conversationId));
        authorization.registerResource(actor, new ResourceRef("run", runId));
        when(researchFactory.create(eq(actor), eq(runId), anyList())).thenReturn(research);
    }

    @AfterEach
    void cleanup() {
        if (database == null) return;
        try {
            database.tx(connection -> {
                for (String sql : List.of("DELETE FROM authorization_audit WHERE subject_id=?",
                        "DELETE FROM subject WHERE subject_id=?")) {
                    try (var statement = SecurityDatabase.statement(connection, sql, subject)) {
                        statement.executeUpdate();
                    }
                }
                try (var statement = SecurityDatabase.statement(connection, "DELETE FROM tenant WHERE tenant_id=?", tenant)) {
                    statement.executeUpdate();
                }
                return null;
            });
        } finally {
            database.close();
        }
    }

    @Test
    void logoutWhileResearchWaitsDiscardsLateAdviceAndDraft() throws Exception {
        var checkpoint = new Checkpoint();
        when(research.runWithAuthorizedPublisher(eq(QUESTION), any(), any(), any()))
                .thenAnswer(invocation -> {
                    checkpoint.pause();
                    return ADVICE;
                });
        var agent = agent(boundary(), events::add);

        revokeAtCheckpoint(agent, checkpoint, () -> authentication.revoke(actor));

        assertTrue(events.stream().anyMatch(event -> "SKILL_SELECTED".equals(event.type())));
        assertFalse(events.stream().anyMatch(FormalSkillRevocationPgTest::draftReady));
        assertNoRestrictedPublication(agent);
    }

    @Test
    void membershipRevokedAfterDraftConstructionBlocksActualQueueAdmission() throws Exception {
        var checkpoint = new Checkpoint();
        var draftReady = new AtomicBoolean();
        var current = boundary();
        OperationBoundary pausedBeforeAdmission = new OperationBoundary() {
            @Override
            public void check(Phase phase) { current.check(phase); }

            @Override
            public <T> T start(Phase phase, Supplier<T> work) {
                // The DRAFT success frame has already been observed. The next start is the
                // SKILL_TERMINAL admission, after the runner's preceding authorization check.
                if (phase == Phase.RESULT_PUBLISH && draftReady.compareAndSet(true, false)) checkpoint.pause();
                return current.start(phase, work);
            }
        };
        when(research.runWithAuthorizedPublisher(eq(QUESTION), any(), any(), any())).thenReturn(ADVICE);
        var agent = agent(pausedBeforeAdmission, event -> {
            events.add(event);
            if (draftReady(event)) draftReady.set(true);
        });

        revokeAtCheckpoint(agent, checkpoint, () -> authorization.setMembershipActive(actor, false));

        assertTrue(events.stream().anyMatch(FormalSkillRevocationPgTest::draftReady));
        assertNoRestrictedPublication(agent);
    }

    private ActorBoundary boundary() {
        return new ActorBoundary(authorization, actor, List.of(
                new Permission("agent.execute", null),
                new Permission("skill.execute", null),
                new Permission("knowledge.read", null),
                new Permission("conversation.readOwn", new ResourceRef("conversation", conversationId)),
                new Permission("run.readOwn", new ResourceRef("run", runId))
        ), runId);
    }

    private ToolCallAgent agent(OperationBoundary boundary, Consumer<AgentExecutionEvent> observe) {
        var registry = new SkillRegistry(SkillCatalogue.formalBuiltIns());
        var legacy = mock(SkillRunner.class);
        var provider = new ItTicketSkillProvider(legacy, registry);
        provider.setScopedSkillRunner(new ScopedSkillRunner(registry, researchFactory, authorization));
        var agent = provider.create(actor, new AgentRunOptions("it-ticket-analysis", "v4"));
        agent.bindOperationBoundary(boundary);
        agent.setConversationId(conversationId);
        agent.setRunId(runId);
        agent.restoreMessages(List.of(new UserMessage(QUESTION)));
        agent.setEventObserver(observe);
        return agent;
    }

    private void revokeAtCheckpoint(ToolCallAgent agent, Checkpoint checkpoint, Runnable revoke) throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try {
            var execution = executor.submit(agent::step);
            try {
                assertTrue(checkpoint.entered.await(10, TimeUnit.SECONDS), "Skill did not reach the selected phase");
                revoke.run();
            } finally {
                checkpoint.resume.countDown();
            }
            var error = assertThrows(ExecutionException.class, () -> execution.get(10, TimeUnit.SECONDS));
            assertEquals("UNAUTHENTICATED", assertInstanceOf(SecurityFailure.class, error.getCause()).code());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "Skill test worker did not stop");
        }
    }

    private static boolean draftReady(AgentExecutionEvent event) {
        if (!"SKILL_STEP".equals(event.type())) return false;
        try {
            var body = JSON.readTree(event.content());
            return "DRAFT".equals(body.path("step").asText()) && "SUCCEEDED".equals(body.path("status").asText());
        } catch (JsonProcessingException malformed) {
            throw new AssertionError("Expected a Skill step payload", malformed);
        }
    }

    private void assertNoRestrictedPublication(ToolCallAgent agent) {
        assertFalse(events.stream().anyMatch(event -> List.of("SKILL_TERMINAL", "ANSWER_DELTA").contains(event.type())));
        assertFalse(events.toString().contains(ADVICE));
        assertFalse(agent.getMessageList().stream().anyMatch(AssistantMessage.class::isInstance));
        int denied = database.tx(connection -> {
            try (var statement = SecurityDatabase.statement(connection, """
                    SELECT count(*) FROM authorization_audit
                    WHERE subject_id=? AND run_id=? AND phase='RESULT_PUBLISH'
                      AND action='skill.execute' AND allowed=false AND code='UNAUTHENTICATED'
                    """, subject, runId); var rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        });
        assertTrue(denied > 0);
    }

    private static class Checkpoint {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch resume = new CountDownLatch(1);

        void pause() {
            entered.countDown();
            try {
                assertTrue(resume.await(10, TimeUnit.SECONDS), "Revocation did not finish");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        }
    }
}
