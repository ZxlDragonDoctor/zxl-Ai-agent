package com.zxl.zxlaiagent.multiagent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Task {
    private String id;
    private String parentId;
    private String description;
    private TaskStatus status;
    private String assignedAgentId;
    private List<String> dependencies;
    private Map<String, Object> input;
    private String result;
    private String error;
    private int priority;
    private long createdAt;
    private long startedAt;
    private long completedAt;

    public static Task create(String description) {
        return Task.builder()
                .id(UUID.randomUUID().toString())
                .description(description)
                .status(TaskStatus.PENDING)
                .dependencies(new java.util.ArrayList<>())
                .createdAt(System.currentTimeMillis())
                .priority(5)
                .build();
    }

    public static Task create(String description, List<String> dependencies) {
        Task task = create(description);
        task.setDependencies(dependencies != null ? dependencies : new java.util.ArrayList<>());
        return task;
    }
}
