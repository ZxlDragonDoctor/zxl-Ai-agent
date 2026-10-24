package com.zxl.zxlaiagent.knowledge.retrieval;

import java.util.*;

/** Equal channel weights, rank starts at one, fixed rank constant 60. */
public final class RankFusion {
    public static List<RetrievalService.Hit> fuse(List<RetrievalService.Hit> vector, List<RetrievalService.Hit> keyword, int limit) {
        if (limit < 1 || limit > 40) throw new IllegalArgumentException("Fusion limit must be 1..40");
        Map<String, RetrievalService.Hit> merged = new HashMap<>();
        add(merged, vector, true); add(merged, keyword, false);
        return merged.values().stream().sorted(Comparator.comparingDouble(RetrievalService.Hit::fusionScore).reversed()
                .thenComparing(RetrievalService.Hit::chunkId)).limit(limit).toList();
    }
    private static void add(Map<String, RetrievalService.Hit> merged, List<RetrievalService.Hit> input, boolean vector) {
        Set<String> seen = new HashSet<>(); int rank = 0;
        for (var hit : input) {
            String key = hit.documentId() + ":" + hit.revisionId() + ":" + hit.chunkId();
            if (!seen.add(key)) continue;
            rank++;
            var previous = merged.getOrDefault(key, hit);
            List<String> channels = new ArrayList<>(previous.channels());
            String channel = vector ? "vector" : "keyword";
            if (!channels.contains(channel)) channels.add(channel);
            merged.put(key, new RetrievalService.Hit(hit.chunkId(),hit.documentId(),hit.revisionId(),hit.generation(),hit.text(),hit.startLine(),hit.endLine(),hit.startUtf16(),hit.endUtf16(),
                    vector ? hit.distance() : previous.distance(), vector ? Integer.valueOf(rank) : previous.vectorRank(), vector ? previous.keywordRank() : Integer.valueOf(rank),
                    (merged.containsKey(key) ? previous.fusionScore() : 0) + 1d/(60+rank), null, List.copyOf(channels)));
        }
    }
    private RankFusion() { }
}
