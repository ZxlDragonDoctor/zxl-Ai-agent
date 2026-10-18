package com.zxl.zxlaiagent.conversation.model;

import com.zxl.zxlaiagent.agent.model.AgentEndReason;
import com.zxl.zxlaiagent.agent.model.AgentExecutionEvent;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record RunRecord(UUID runId, Instant startedAt, Instant finishedAt, AgentEndReason endReason,
                        String status, String answer, String error, List<AgentExecutionEvent> events) {
    public RunRecord {
        Objects.requireNonNull(runId);
        Objects.requireNonNull(startedAt);
        Objects.requireNonNull(status);
        events = List.copyOf(events);
        answer = answer == null ? "" : answer;
        if (!List.of("running", "completed", "error", "interrupted", "limit", "terminated").contains(status)) {
            throw new IllegalArgumentException("不支持的执行状态");
        }
        if ("running".equals(status) ? (finishedAt != null || endReason != null)
                : (finishedAt == null || endReason == null)) {
            throw new IllegalArgumentException("执行状态与完成时间不一致");
        }
    }

    public static RunRecord started(UUID id, Instant at) {
        return new RunRecord(id, at, null, null, "running", "", null, List.of());
    }

    public RunRecord finish(AgentEndReason reason, String text, String error, List<AgentExecutionEvent> trace) {
        String state = switch (reason) {
            case STEP_LIMIT -> "limit";
            case TERMINATED_BY_TOOL -> "terminated";
            case OUTPUT_INTERRUPTED -> "interrupted";
            default -> reason.isFailure() ? "error" : "completed";
        };
        return new RunRecord(runId, startedAt, Instant.now(), reason, state, text, error, trace);
    }
}
