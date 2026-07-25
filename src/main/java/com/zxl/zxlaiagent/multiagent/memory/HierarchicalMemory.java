package com.zxl.zxlaiagent.multiagent.memory;

import lombok.Builder;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

@Component
@Slf4j
public class HierarchicalMemory {

    @Value
    @Builder
    public static class MemoryEntry {
        String id;
        long timestamp;
        MemoryLevel level;
        String agentId;
        String sessionId;
        String role;
        String content;
        Map<String, Object> metadata;
        double importance;
    }

    public enum MemoryLevel {
        EPISODIC,
        SEMANTIC,
        PROCEDURAL,
        WORKING
    }

    private final Map<String, List<MemoryEntry>> workingMemory = new ConcurrentHashMap<>();
    private final Map<String, List<MemoryEntry>> episodicMemory = new ConcurrentHashMap<>();
    private final Map<String, List<MemoryEntry>> semanticMemory = new ConcurrentHashMap<>();
    private final Map<String, List<MemoryEntry>> proceduralMemory = new ConcurrentHashMap<>();

    private static final int WORKING_MEMORY_LIMIT = 50;
    private static final double IMPORTANCE_THRESHOLD = 0.7;

    public void store(String agentId, String sessionId, String role, String content,
                      MemoryLevel level, double importance) {
        MemoryEntry entry = MemoryEntry.builder()
                .id(UUID.randomUUID().toString())
                .timestamp(System.currentTimeMillis())
                .level(level)
                .agentId(agentId)
                .sessionId(sessionId)
                .role(role)
                .content(content)
                .importance(importance)
                .metadata(new HashMap<>())
                .build();

        if (importance >= IMPORTANCE_THRESHOLD) {
            consolidateToLongTerm(entry);
        }

        var store = getStoreForLevel(level, agentId);
        store.add(entry);

        if (level == MemoryLevel.WORKING && store.size() > WORKING_MEMORY_LIMIT) {
            var toConsolidate = store.stream()
                    .sorted(Comparator.comparingDouble(MemoryEntry::getImportance).reversed())
                    .skip(WORKING_MEMORY_LIMIT / 2)
                    .toList();
            toConsolidate.forEach(this::consolidateToLongTerm);
            var toKeep = store.stream()
                    .sorted(Comparator.comparingDouble(MemoryEntry::getImportance).reversed())
                    .limit(WORKING_MEMORY_LIMIT / 2)
                    .collect(Collectors.toList());
            store.clear();
            store.addAll(toKeep);
        }
    }

    public void storeConversation(String agentId, String sessionId, List<Message> messages) {
        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);
            String role = msg instanceof UserMessage ? "user" :
                    msg instanceof AssistantMessage ? "assistant" : "system";
            double importance = calculateImportance(msg.getText());
            store(agentId, sessionId, role, msg.getText(),
                    i < 5 ? MemoryLevel.WORKING : MemoryLevel.EPISODIC, importance);
        }
    }

    public List<MemoryEntry> recall(String agentId, String query, int limit) {
        List<MemoryEntry> results = new ArrayList<>();

        results.addAll(recallFromLevel(agentId, MemoryLevel.WORKING, query, limit));
        results.addAll(recallFromLevel(agentId, MemoryLevel.EPISODIC, query, limit - results.size()));
        results.addAll(recallFromLevel(agentId, MemoryLevel.SEMANTIC, query, limit - results.size()));

        return results.stream()
                .sorted(Comparator.comparingDouble(MemoryEntry::getImportance).reversed())
                .limit(limit)
                .toList();
    }

    public List<MemoryEntry> getRecentContext(String agentId, String sessionId, int lastN) {
        List<MemoryEntry> all = new ArrayList<>();
        all.addAll(getStoreForLevel(MemoryLevel.WORKING, agentId));
        all.addAll(getStoreForLevel(MemoryLevel.EPISODIC, agentId));

        return all.stream()
                .filter(e -> e.getSessionId().equals(sessionId))
                .sorted(Comparator.comparingLong(MemoryEntry::getTimestamp).reversed())
                .limit(lastN)
                .toList();
    }

    public void clearSession(String agentId, String sessionId) {
        Arrays.asList(MemoryLevel.values()).forEach(level -> {
            var store = getStoreForLevel(level, agentId);
            store.removeIf(e -> e.getSessionId().equals(sessionId));
        });
    }

    public Map<String, Object> getStats(String agentId) {
        return Map.of(
                "workingMemory", getStoreForLevel(MemoryLevel.WORKING, agentId).size(),
                "episodicMemory", getStoreForLevel(MemoryLevel.EPISODIC, agentId).size(),
                "semanticMemory", getStoreForLevel(MemoryLevel.SEMANTIC, agentId).size(),
                "proceduralMemory", getStoreForLevel(MemoryLevel.PROCEDURAL, agentId).size()
        );
    }

    private void consolidateToLongTerm(MemoryEntry entry) {
        MemoryLevel targetLevel = switch (entry.getLevel()) {
            case WORKING -> MemoryLevel.EPISODIC;
            case EPISODIC -> MemoryLevel.SEMANTIC;
            case SEMANTIC -> MemoryLevel.PROCEDURAL;
            default -> MemoryLevel.EPISODIC;
        };
        var targetStore = getStoreForLevel(targetLevel, entry.getAgentId());
        if (targetStore.stream().noneMatch(e -> e.getContent().equals(entry.getContent()))) {
            targetStore.add(MemoryEntry.builder()
                    .id(entry.getId())
                    .timestamp(entry.getTimestamp())
                    .level(targetLevel)
                    .agentId(entry.getAgentId())
                    .sessionId(entry.getSessionId())
                    .role(entry.getRole())
                    .content(entry.getContent())
                    .importance(entry.getImportance() * 0.9)
                    .build());
        }
    }

    private List<MemoryEntry> recallFromLevel(String agentId, MemoryLevel level, String query, int limit) {
        if (limit <= 0) return List.of();
        var store = getStoreForLevel(level, agentId);
        if (query == null || query.isBlank()) {
            return store.stream()
                    .sorted(Comparator.comparingDouble(MemoryEntry::getImportance).reversed())
                    .limit(limit)
                    .toList();
        }
        String queryLower = query.toLowerCase();
        return store.stream()
                .filter(e -> e.getContent() != null &&
                        e.getContent().toLowerCase().contains(queryLower))
                .sorted(Comparator.comparingDouble(MemoryEntry::getImportance).reversed())
                .limit(limit)
                .toList();
    }

    private double calculateImportance(String content) {
        if (content == null || content.isBlank()) return 0.0;
        String lower = content.toLowerCase();
        double score = 0.5;
        String[] keywords = {"important", "记住", "关键", "必须", "always", "never", "关键",
                "重要", "核心", "crucial", "critical", "essential", "vital"};
        for (String kw : keywords) {
            if (lower.contains(kw)) score += 0.1;
        }
        if (content.length() > 200) score += 0.2;
        if (content.contains("```") || content.contains("code")) score += 0.1;
        return Math.min(1.0, score);
    }

    private List<MemoryEntry> getStoreForLevel(MemoryLevel level, String agentId) {
        return switch (level) {
            case WORKING -> workingMemory.computeIfAbsent(agentId, k -> new CopyOnWriteArrayList<>());
            case EPISODIC -> episodicMemory.computeIfAbsent(agentId, k -> new CopyOnWriteArrayList<>());
            case SEMANTIC -> semanticMemory.computeIfAbsent(agentId, k -> new CopyOnWriteArrayList<>());
            case PROCEDURAL -> proceduralMemory.computeIfAbsent(agentId, k -> new CopyOnWriteArrayList<>());
        };
    }
}
