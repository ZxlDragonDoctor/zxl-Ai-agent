package com.zxl.zxlaiagent.multiagent.model;

import com.fasterxml.jackson.annotation.*;
import lombok.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

@Getter
@Setter
@NoArgsConstructor
public class Task {
    private String id;
    private String parentId;
    private String description;
    private List<String> dependencies = new ArrayList<>();
    private volatile Map<String, Object> input;
    private int priority;
    private long createdAt;
    @Getter(AccessLevel.NONE) @Setter(AccessLevel.NONE) @JsonIgnore
    private final AtomicReference<TaskSnapshot> execution = new AtomicReference<>(TaskSnapshot.pending());

    @JsonProperty("execution") public TaskSnapshot snapshot() { return execution.get(); }
    /** Only Jackson can restore a wire snapshot. PlanValidator still rejects every non-pristine entry. */
    @JsonSetter("execution") private void restoreExecution(TaskSnapshot snapshot) {
        execution.set(Objects.requireNonNull(snapshot, "execution"));
    }
    boolean compareExecution(TaskSnapshot expected, TaskSnapshot next) { return execution.compareAndSet(expected, next); }
    @JsonIgnore public TaskStatus getStatus() { return snapshot().status(); }
    @JsonIgnore public String getResult() { return snapshot().result(); }
    @JsonIgnore public String getAssignedAgentId() { return snapshot().assignedAgentId(); }
    @JsonIgnore public int getModelCalls() { return snapshot().modelCalls(); }
    @JsonIgnore public long getStartedAt() { return snapshot().startedAt(); }
    @JsonIgnore public long getCompletedAt() { return snapshot().completedAt(); }
    @JsonIgnore public String getError() { return snapshot().failure() == null ? null : snapshot().failure().message(); }
    @JsonIgnore public String getErrorCode() { return snapshot().failure() == null ? null : snapshot().failure().code(); }
    @JsonIgnore public String getErrorPath() { return snapshot().failure() == null ? null : snapshot().failure().path(); }

    public static Task create(String description) {
        Task task = new Task();
        task.id = UUID.randomUUID().toString();
        task.description = description;
        task.createdAt = System.currentTimeMillis();
        task.priority = 5;
        return task;
    }
    public static Task create(String description, List<String> dependencies) {
        Task task = create(description);
        task.dependencies = dependencies == null ? new ArrayList<>() : new ArrayList<>(dependencies);
        return task;
    }
}
