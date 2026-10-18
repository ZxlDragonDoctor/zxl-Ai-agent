package com.zxl.zxlaiagent.agent.model;

public enum AgentEndReason {
    ANSWER_READY(false), STEP_LIMIT(false), TERMINATED_BY_TOOL(false), TOOL_RETURNED(false),
    MODEL_ERROR(true), TOOL_ERROR(true), EXECUTION_ERROR(true), OUTPUT_INTERRUPTED(true), PERSISTENCE_ERROR(true);

    private final boolean failure;
    AgentEndReason(boolean failure) { this.failure = failure; }
    public boolean isFailure() { return failure; }
}
