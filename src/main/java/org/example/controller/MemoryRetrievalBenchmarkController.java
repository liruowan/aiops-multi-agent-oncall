package org.example.controller;

import lombok.Getter;
import lombok.Setter;
import org.example.memory.MemoryEntry;
import org.example.memory.MemoryRetriever;
import org.example.memory.MemoryType;
import org.example.memory.TokenEstimator;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/memory/retrieval")
public class MemoryRetrievalBenchmarkController {

    private final MemoryRetriever memoryRetriever;
    private final TokenEstimator tokenEstimator;

    public MemoryRetrievalBenchmarkController(MemoryRetriever memoryRetriever,
                                              TokenEstimator tokenEstimator) {
        this.memoryRetriever = memoryRetriever;
        this.tokenEstimator = tokenEstimator;
    }

    @PostMapping("/benchmark")
    public ResponseEntity<BenchmarkResponse> benchmark(@RequestBody BenchmarkRequest request) {
        String sessionId = request.getSessionId() == null || request.getSessionId().isBlank()
                ? "retrieval-benchmark-" + UUID.randomUUID()
                : request.getSessionId();

        List<MemoryEntry> facts = toMemoryEntries(sessionId, request.getFacts());
        List<QueryResult> queryResults = new ArrayList<>();

        int totalHits = 0;
        int totalExactCoreHits = 0;
        int totalExpectedCoreFacts = 0;
        int totalReturned = 0;
        int totalIrrelevantReturned = 0;
        int totalBaselineReturned = 0;
        int totalBaselineIrrelevant = 0;

        for (QueryCase queryCase : request.getQueries()) {
            List<MemoryEntry> retrieved = memoryRetriever.retrieve(sessionId, queryCase.getQuery(), facts);
            Set<String> expectedIds = new HashSet<>(queryCase.getExpectedFactIds());
            Set<String> relevantIds = expectedIds;
            Set<String> returnedIds = new HashSet<>(retrieved.stream().map(MemoryEntry::getId).toList());

            int hitCount = 0;
            for (String returnedId : returnedIds) {
                if (relevantIds.contains(returnedId)) {
                    hitCount++;
                }
            }
            int exactCoreHitCount = 0;
            for (String expectedId : expectedIds) {
                if (returnedIds.contains(expectedId)) {
                    exactCoreHitCount++;
                }
            }
            int irrelevantCount = 0;
            for (MemoryEntry entry : retrieved) {
                if (!relevantIds.contains(entry.getId())) {
                    irrelevantCount++;
                }
            }

            int baselineReturned = facts.size();
            int baselineIrrelevant = Math.max(0, baselineReturned - relevantIds.size());

            totalHits += hitCount;
            totalExactCoreHits += exactCoreHitCount;
            totalExpectedCoreFacts += expectedIds.size();
            totalReturned += retrieved.size();
            totalIrrelevantReturned += irrelevantCount;
            totalBaselineReturned += baselineReturned;
            totalBaselineIrrelevant += baselineIrrelevant;

            QueryResult result = new QueryResult();
            result.setQueryId(queryCase.getId());
            result.setQuery(queryCase.getQuery());
            result.setExpectedFactIds(queryCase.getExpectedFactIds());
            result.setRelevantFactIds(new ArrayList<>(relevantIds));
            result.setReturnedFacts(toFactResults(retrieved));
            result.setExpectedCount(expectedIds.size());
            result.setRelevantCount(relevantIds.size());
            result.setHitCount(hitCount);
            result.setExactCoreHitCount(exactCoreHitCount);
            result.setReturnedCount(retrieved.size());
            result.setIrrelevantCount(irrelevantCount);
            result.setTopKHitRate(retrieved.isEmpty() ? 0.0 : (double) hitCount / retrieved.size());
            result.setExactCoreHitRate(expectedIds.isEmpty() ? 0.0 : (double) exactCoreHitCount / expectedIds.size());
            result.setIrrelevantInjectionRate(retrieved.isEmpty() ? 0.0 : (double) irrelevantCount / retrieved.size());
            result.setBaselineIrrelevantInjectionRate(baselineReturned == 0 ? 0.0
                    : (double) baselineIrrelevant / baselineReturned);
            queryResults.add(result);
        }

        double topKHitRate = totalReturned == 0 ? 0.0 : (double) totalHits / totalReturned;
        double exactCoreHitRate = totalExpectedCoreFacts == 0 ? 0.0
                : (double) totalExactCoreHits / totalExpectedCoreFacts;
        double irrelevantInjectionRate = totalReturned == 0 ? 0.0
                : (double) totalIrrelevantReturned / totalReturned;
        double baselineIrrelevantInjectionRate = totalBaselineReturned == 0 ? 0.0
                : (double) totalBaselineIrrelevant / totalBaselineReturned;
        double irrelevantInjectionReductionRate = baselineIrrelevantInjectionRate == 0.0 ? 0.0
                : (baselineIrrelevantInjectionRate - irrelevantInjectionRate) / baselineIrrelevantInjectionRate;

        BenchmarkResponse response = new BenchmarkResponse();
        response.setSessionId(sessionId);
        response.setTotalFacts(facts.size());
        response.setTotalQueries(request.getQueries().size());
        response.setTotalHits(totalHits);
        response.setTotalExactCoreHits(totalExactCoreHits);
        response.setTotalExpectedCoreFacts(totalExpectedCoreFacts);
        response.setTotalReturnedFacts(totalReturned);
        response.setTotalIrrelevantReturnedFacts(totalIrrelevantReturned);
        response.setTopKHitRate(topKHitRate);
        response.setExactCoreHitRate(exactCoreHitRate);
        response.setIrrelevantInjectionRate(irrelevantInjectionRate);
        response.setBaselineIrrelevantInjectionRate(baselineIrrelevantInjectionRate);
        response.setIrrelevantInjectionReductionRate(irrelevantInjectionReductionRate);
        response.setAvgReturnedFacts(request.getQueries().isEmpty() ? 0.0
                : (double) totalReturned / request.getQueries().size());
        response.setResults(queryResults);
        return ResponseEntity.ok(response);
    }

    private List<MemoryEntry> toMemoryEntries(String sessionId, List<FactInput> facts) {
        List<MemoryEntry> entries = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (FactInput fact : facts) {
            MemoryEntry entry = new MemoryEntry();
            entry.setId(fact.getId());
            entry.setSessionId(sessionId);
            entry.setType(MemoryType.FACT);
            entry.setContent(fact.getContent());
            entry.setCreatedAt(now);
            entry.setEstimatedTokens(tokenEstimator.estimate(fact.getContent()));
            entries.add(entry);
        }
        return entries;
    }

    private List<FactResult> toFactResults(List<MemoryEntry> entries) {
        return entries.stream().map(entry -> {
            FactResult result = new FactResult();
            result.setId(entry.getId());
            result.setContent(entry.getContent());
            return result;
        }).toList();
    }

    @Getter
    @Setter
    public static class BenchmarkRequest {
        private String sessionId;
        private List<FactInput> facts = new ArrayList<>();
        private List<QueryCase> queries = new ArrayList<>();
    }

    @Getter
    @Setter
    public static class FactInput {
        private String id;
        private String content;
    }

    @Getter
    @Setter
    public static class QueryCase {
        private String id;
        private String query;
        private List<String> expectedFactIds = new ArrayList<>();
        private List<String> relevantFactIds = new ArrayList<>();
    }

    @Getter
    @Setter
    public static class BenchmarkResponse {
        private String sessionId;
        private int totalFacts;
        private int totalQueries;
        private int totalHits;
        private int totalExactCoreHits;
        private int totalExpectedCoreFacts;
        private int totalReturnedFacts;
        private int totalIrrelevantReturnedFacts;
        private double topKHitRate;
        private double exactCoreHitRate;
        private double irrelevantInjectionRate;
        private double baselineIrrelevantInjectionRate;
        private double irrelevantInjectionReductionRate;
        private double avgReturnedFacts;
        private List<QueryResult> results = new ArrayList<>();
    }

    @Getter
    @Setter
    public static class QueryResult {
        private String queryId;
        private String query;
        private List<String> expectedFactIds = new ArrayList<>();
        private List<String> relevantFactIds = new ArrayList<>();
        private List<FactResult> returnedFacts = new ArrayList<>();
        private int expectedCount;
        private int relevantCount;
        private int hitCount;
        private int exactCoreHitCount;
        private int returnedCount;
        private int irrelevantCount;
        private double topKHitRate;
        private double exactCoreHitRate;
        private double irrelevantInjectionRate;
        private double baselineIrrelevantInjectionRate;
    }

    @Getter
    @Setter
    public static class FactResult {
        private String id;
        private String content;
    }
}
