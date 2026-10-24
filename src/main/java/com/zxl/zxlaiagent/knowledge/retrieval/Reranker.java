package com.zxl.zxlaiagent.knowledge.retrieval;

import java.time.Duration;
import java.util.*;

public interface Reranker {
    record Score(String chunkId, double score) { }
    List<Score> rank(String originalQuery, List<RetrievalService.Hit> candidates, Duration remaining);
    default String identity() { return "rule-keyword-v1"; }
    static Map<String, Double> validate(List<RetrievalService.Hit> candidates, List<Score> scores) {
        if (scores == null || scores.size() != candidates.size()) throw new IllegalArgumentException("Reranker omitted candidates");
        Set<String> allowed = new HashSet<>(); candidates.forEach(h -> allowed.add(h.chunkId()));
        Map<String,Double> result = new HashMap<>();
        for (var score : scores) {
            if (score == null || !allowed.contains(score.chunkId()) || !Double.isFinite(score.score()) || result.putIfAbsent(score.chunkId(),score.score()) != null)
                throw new IllegalArgumentException("Invalid reranker output");
        }
        return result;
    }
    static Reranker rules() {
        return (query, candidates, remaining) -> {
            var terms = QueryTerms.extract(query);
            return candidates.stream().map(hit -> new Score(hit.chunkId(), terms.stream()
                    .filter(term -> hit.text().toLowerCase(Locale.ROOT).contains(term)).mapToDouble(term -> term.matches(".*[0-9].*") ? 4 : 1).sum())).toList();
        };
    }
}
