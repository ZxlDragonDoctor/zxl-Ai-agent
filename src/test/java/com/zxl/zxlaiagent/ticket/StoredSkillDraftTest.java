package com.zxl.zxlaiagent.ticket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zxl.zxlaiagent.agent.model.AgentEndReason;
import com.zxl.zxlaiagent.agent.model.AgentExecutionEvent;
import com.zxl.zxlaiagent.conversation.model.ConversationDetail;
import com.zxl.zxlaiagent.conversation.model.RunRecord;
import com.zxl.zxlaiagent.security.SecurityFailure;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class StoredSkillDraftTest {
    private static final UUID CONVERSATION = UUID.randomUUID();
    private static final UUID RUN = UUID.randomUUID();
    private static final Instant NOW = Instant.now();
    private static final String RESULT = """
            {"status":"SUCCEEDED","code":"OK","snapshot":{"runId":"%s","executorVersion":"authenticated-skill-executor-v1",
              "definition":{"skillId":"it-ticket-analysis","version":"v4","outputSchemaVersion":"ticket-draft-v2"}},
              "draft":{"schemaVersion":"ticket-draft-v2","title":"SSO 403","problem":"Login blocked",
              "advice":"Contact the support desk","created":false,"requiresApproval":true}}
            """.formatted(RUN);
    private final StoredSkillDraft resolver = new StoredSkillDraft(new ObjectMapper());

    @Test
    void extractsDraftFromPersistedSuccessfulFormalRun() {
        var draft = resolver.resolve(detail(false, RESULT, RUN), RUN);
        assertEquals("SSO 403", draft.title());
        assertEquals("Login blocked", draft.problem());
    }

    @Test
    void rejectsArchivedHistoryDemoOutputMissingRunAndMalformedDraft() {
        for (var detail : List.of(detail(true, RESULT, RUN),
                detail(false, RESULT.replace("authenticated-skill-executor-v1", "demo"), RUN),
                detail(false, RESULT, UUID.randomUUID()),
                detail(false, RESULT.replace("false", "true"), RUN),
                detail(false, RESULT.replace("SUCCEEDED", "NO_EVIDENCE"), RUN),
                detail(false, "{} {}", RUN))) {
            assertEquals("TICKET_DRAFT_UNAVAILABLE",
                    assertThrows(SecurityFailure.class, () -> resolver.resolve(detail, RUN)).code());
        }
    }

    private static ConversationDetail detail(boolean archived, String content, UUID eventRun) {
        var event = AgentExecutionEvent.of("SKILL_TERMINAL", 4, content, "succeeded")
                .withIdentity(CONVERSATION.toString(), eventRun.toString());
        var run = RunRecord.started(RUN, NOW).finish(AgentEndReason.ANSWER_READY, "", null, List.of(event));
        return new ConversationDetail(CONVERSATION, "IT", NOW, NOW, 1, List.of(), List.of(run),
                false, "it-ticket-skill", archived);
    }
}
