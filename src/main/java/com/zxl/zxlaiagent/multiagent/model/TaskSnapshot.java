package com.zxl.zxlaiagent.multiagent.model;

/** 一次原子读取得到一致的状态、产物、根因和时间。 */
public record TaskSnapshot(TaskStatus status, String result, WorkflowError failure,
                           String assignedAgentId, int modelCalls, long startedAt, long completedAt) {
    public static TaskSnapshot pending() { return new TaskSnapshot(TaskStatus.PENDING, null, null, null, 0, 0, 0); }
}
