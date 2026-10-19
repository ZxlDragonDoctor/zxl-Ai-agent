package com.zxl.zxlaiagent.multiagent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Plan {
    private String id;
    private String goal;
    private List<Task> tasks;
    private PlanStatus status;
    private String createdBy;
    private long createdAt;

    public static Plan create(String goal) {
        return Plan.builder()
                .id(UUID.randomUUID().toString())
                .goal(goal)
                .tasks(new ArrayList<>())
                .status(PlanStatus.CREATED)
                .createdAt(System.currentTimeMillis())
                .build();
    }

    public void addTask(Task task) {
        tasks.add(task);
    }

    public boolean isComplete() {
        return tasks != null
                && !tasks.isEmpty()
                && tasks.stream().allMatch(task -> task.getStatus() == TaskStatus.COMPLETED);
    }

    public List<Task> getReadyTasks() {
        return tasks.stream()
                .filter(t -> t.getStatus() == TaskStatus.PENDING)
                .filter(t -> t.getDependencies().isEmpty() ||
                        t.getDependencies().stream().allMatch(depId ->
                                tasks.stream().anyMatch(t2 -> t2.getId().equals(depId) && t2.getStatus() == TaskStatus.COMPLETED)))
                .toList();
    }
}
