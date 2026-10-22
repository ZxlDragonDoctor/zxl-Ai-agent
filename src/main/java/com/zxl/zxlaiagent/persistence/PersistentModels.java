package com.zxl.zxlaiagent.persistence;

import java.util.List;

/** Database facts; never serialize a live Future or worker thread. */
public final class PersistentModels {
    private PersistentModels() {}
    public record Run(String id, String status, long acceptedAt, long deadlineAt, long version, String errorCode) {}
    public record Task(String id, String runId, String status, String operationId, String title, String description,
                       long version, String leaseOwner, Long leaseUntil, long fenceToken) {}
    public record Attempt(String id, String taskId, int attemptNo, long fenceToken, String status, long startedAt,
                          Long finishedAt, String errorCode) {}
    public record Event(long sequence, String type, String payload, long createdAt) {}
    public record Ticket(String id, String operationId, String title, String description, long createdAt) {}
    public record Snapshot(Run run, Task task, List<Attempt> attempts, List<Event> events, Ticket ticket) {}
    public record Claim(String taskId, String runId, String owner, long token) {}
    public static final class Conflict extends RuntimeException {
        public final String code;
        public Conflict(String code) { super(code); this.code = code; }
    }
}
