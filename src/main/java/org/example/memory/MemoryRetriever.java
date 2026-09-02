package org.example.memory;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class MemoryRetriever {
    private static final Pattern ENTITY_PATTERN = Pattern.compile(
            "\\b[a-zA-Z0-9][a-zA-Z0-9_.-]{2,}\\b|High[A-Z][A-Za-z0-9]+|Service[A-Z][A-Za-z0-9]+");

    private final MemoryVectorStore vectorStore;
    private final MemoryProperties properties;

    public MemoryRetriever(MemoryVectorStore vectorStore, MemoryProperties properties) {
        this.vectorStore = vectorStore;
        this.properties = properties;
    }

    public List<MemoryEntry> retrieve(String sessionId, String query, List<MemoryEntry> facts) {
        Map<String, ScoredMemory> candidates = new HashMap<>();
        for (MemoryEntry fact : facts) {
            candidates.put(fact.getId(), new ScoredMemory(fact, 0.0));
        }
        for (MemoryVectorStore.VectorCandidate vectorCandidate :
                vectorStore.search(sessionId, query, Math.max(properties.getRetrievalTopK() * 3, 10))) {
            candidates.compute(vectorCandidate.entry().getId(), (id, current) ->
                    new ScoredMemory(vectorCandidate.entry(), vectorCandidate.similarity()));
        }

        Set<String> queryTerms = terms(query);
        return candidates.values().stream()
                .map(candidate -> score(candidate, queryTerms))
                .filter(candidate -> candidate.score >= properties.getRetrievalMinScore())
                .sorted(Comparator.comparingDouble(ScoredMemory::score).reversed())
                .limit(properties.getRetrievalTopK())
                .map(ScoredMemory::entry)
                .toList();
    }

    private ScoredMemory score(ScoredMemory candidate, Set<String> queryTerms) {
        Set<String> contentTerms = terms(candidate.entry.getContent());
        long matched = queryTerms.stream().filter(contentTerms::contains).count();
        double keywordScore = queryTerms.isEmpty() ? 0.0 : (double) matched / queryTerms.size();
        long ageDays = Math.max(0, Duration.between(
                Instant.ofEpochMilli(candidate.entry.getCreatedAt()), Instant.now()).toDays());
        double recencyScore = 1.0 / (1.0 + ageDays / 30.0);
        double vectorWeight = candidate.vectorScore > 0 ? 0.6 : 0.0;
        double keywordWeight = candidate.vectorScore > 0 ? 0.3 : 0.9;
        double finalScore =
                candidate.vectorScore * vectorWeight + keywordScore * keywordWeight + recencyScore * 0.1;
        return new ScoredMemory(candidate.entry, candidate.vectorScore, finalScore);
    }

    private Set<String> terms(String text) {
        Set<String> result = new HashSet<>();
        if (text == null) {
            return result;
        }
        Matcher matcher = ENTITY_PATTERN.matcher(text);
        while (matcher.find()) {
            result.add(matcher.group().toLowerCase(Locale.ROOT));
        }
        for (String part : text.toLowerCase(Locale.ROOT).split("[\\s,，。！？:：/]+")) {
            if (part.length() >= 2) {
                result.add(part);
            }
        }
        return result;
    }

    private record ScoredMemory(MemoryEntry entry, double vectorScore, double score) {
        ScoredMemory(MemoryEntry entry, double vectorScore) {
            this(entry, vectorScore, 0.0);
        }
    }
}
