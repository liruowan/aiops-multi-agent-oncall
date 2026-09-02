package org.example.memory;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class MemoryManager {
    private static final Logger logger = LoggerFactory.getLogger(MemoryManager.class);

    private final MemoryStore memoryStore;
    private final MemoryRetriever memoryRetriever;
    private final MemoryVectorStore vectorStore;
    private final MemoryCurator curator;
    private final TokenEstimator tokenEstimator;
    private final MemoryProperties properties;
    private final Map<String, MemorySession> sessions = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> sessionLocks = new ConcurrentHashMap<>();
    private final ExecutorService compressionExecutor = Executors.newFixedThreadPool(2);

    public MemoryManager(MemoryStore memoryStore,
                         MemoryRetriever memoryRetriever,
                         MemoryVectorStore vectorStore,
                         MemoryCurator curator,
                         TokenEstimator tokenEstimator,
                         MemoryProperties properties) {
        this.memoryStore = memoryStore;
        this.memoryRetriever = memoryRetriever;
        this.vectorStore = vectorStore;
        this.curator = curator;
        this.tokenEstimator = tokenEstimator;
        this.properties = properties;
    }

    public void addUserMessage(String sessionId, String content) {
        addConversationMessage(sessionId, "user", content);
    }

    public void addAssistantMessage(String sessionId, String content) {
        addConversationMessage(sessionId, "assistant", content);
    }

    public MemoryContext buildContextForQuery(String sessionId, String query) {
        ReentrantLock lock = lockFor(sessionId);
        lock.lock();
        try {
            MemorySession session = session(sessionId);
            MemoryContext context = new MemoryContext();
            if (!session.getSummaries().isEmpty()) {
                context.setHistoricalSummary(session.getSummaries()
                        .get(session.getSummaries().size() - 1).getContent());
            }
            List<MemoryEntry> recent = recentMessages(session.getRecentMessages(), properties.getRecentRounds() * 2);
            if (!recent.isEmpty()) {
                MemoryEntry last = recent.get(recent.size() - 1);
                if ("user".equals(last.getMetadata().get("role")) && query.equals(last.getContent())) {
                    recent = new ArrayList<>(recent.subList(0, recent.size() - 1));
                }
            }
            context.setRecentMessages(trimToBudget(recent, properties.getContextTokenBudget()));
            context.setRelevantFacts(memoryRetriever.retrieve(sessionId, query, session.getFacts()));
            context.setOpenTasks(recentMessages(session.getOpenTasks(), properties.getOpenTaskTopK()));
            return context;
        } finally {
            lock.unlock();
        }
    }

    public void scheduleCompressionIfNeeded(String sessionId) {
        CompressionSnapshot snapshot = prepareCompression(sessionId);
        if (snapshot == null) {
            return;
        }
        compressionExecutor.submit(() -> compress(snapshot));
    }

    public void recordPromptTokens(String sessionId, int promptTokens, MemoryContext context) {
        ReentrantLock lock = lockFor(sessionId);
        lock.lock();
        try {
            MemorySession session = session(sessionId);
            session.setLastPromptTokens(promptTokens);
            if (!session.getSummaries().isEmpty()
                    && session.getLastCompressedOriginalTokens() > 0
                    && session.getLastPromptTokensAfterCompression() == 0) {
                int compressedContextTokens = compressedContextTokens(context);
                int hypotheticalPromptTokens = Math.max(promptTokens,
                        promptTokens - compressedContextTokens + session.getLastCompressedOriginalTokens());
                session.setLastPromptTokensBeforeCompression(hypotheticalPromptTokens);
                session.setLastPromptTokensAfterCompression(promptTokens);
                double reductionRatio = (double) (hypotheticalPromptTokens - promptTokens)
                        / hypotheticalPromptTokens;
                session.setLastPromptTokenReductionRatio(reductionRatio);
            }
            touchAndSave(session);
        } finally {
            lock.unlock();
        }
    }

    public void clearConversation(String sessionId) {
        ReentrantLock lock = lockFor(sessionId);
        lock.lock();
        try {
            MemorySession session = session(sessionId);
            session.getRecentMessages().clear();
            session.getSummaries().clear();
            session.getOpenTasks().clear();
            session.setSessionVersion(session.getSessionVersion() + 1);
            session.setCompressionStatus("IDLE");
            touchAndSave(session);
        } finally {
            lock.unlock();
        }
    }

    public MemoryStatus getStatus(String sessionId) {
        ReentrantLock lock = lockFor(sessionId);
        lock.lock();
        try {
            MemorySession session = session(sessionId);
            MemoryStatus status = new MemoryStatus();
            status.setSessionId(sessionId);
            status.setRecentMessageCount(session.getRecentMessages().size());
            status.setSummaryCount(session.getSummaries().size());
            status.setLongTermFactCount(session.getFacts().size());
            status.setOpenTaskCount(session.getOpenTasks().size());
            status.setEstimatedTokens(totalTokens(session.getRecentMessages()));
            status.setCompressionStatus(session.getCompressionStatus());
            status.setLastCompressionRatio(session.getLastCompressionRatio());
            status.setLastCompressionDurationMs(session.getLastCompressionDurationMs());
            status.setLastPromptTokens(session.getLastPromptTokens());
            status.setLastPromptTokensBeforeCompression(session.getLastPromptTokensBeforeCompression());
            status.setLastPromptTokensAfterCompression(session.getLastPromptTokensAfterCompression());
            status.setLastPromptTokenReductionRatio(session.getLastPromptTokenReductionRatio());
            status.setLastUpdatedAt(session.getLastUpdatedAt());
            return status;
        } finally {
            lock.unlock();
        }
    }

    private void addConversationMessage(String sessionId, String role, String content) {
        ReentrantLock lock = lockFor(sessionId);
        lock.lock();
        try {
            MemorySession session = session(sessionId);
            MemoryEntry entry = entry(sessionId, MemoryType.CONVERSATION, content);
            entry.getMetadata().put("role", role);
            session.getRecentMessages().add(entry);
            session.setSessionVersion(session.getSessionVersion() + 1);
            touchAndSave(session);
        } finally {
            lock.unlock();
        }
    }

    private CompressionSnapshot prepareCompression(String sessionId) {
        ReentrantLock lock = lockFor(sessionId);
        lock.lock();
        try {
            MemorySession session = session(sessionId);
            if (!"IDLE".equals(session.getCompressionStatus())) {
                return null;
            }
            int keepCount = properties.getRecentRounds() * 2;
            int pairCount = session.getRecentMessages().size() / 2;
            int tokens = totalTokens(session.getRecentMessages());
            if (pairCount <= properties.getCompressionRoundThreshold()
                    && tokens <= properties.getCompressionTokenThreshold()) {
                return null;
            }
            int compressCount = Math.max(0, session.getRecentMessages().size() - keepCount);
            if (compressCount == 0) {
                return null;
            }
            List<MemoryEntry> entries = new ArrayList<>(session.getRecentMessages().subList(0, compressCount));
            session.setCompressionStatus("RUNNING");
            if (session.getLastPromptTokens() > 0) {
                session.setLastPromptTokensBeforeCompression(session.getLastPromptTokens());
                session.setLastPromptTokensAfterCompression(0);
                session.setLastPromptTokenReductionRatio(0.0);
                session.setLastCompressedOriginalTokens(totalTokens(entries));
            }
            touchAndSave(session);
            return new CompressionSnapshot(sessionId, session.getSessionVersion(), entries, tokens);
        } finally {
            lock.unlock();
        }
    }

    private void compress(CompressionSnapshot snapshot) {
        long started = System.currentTimeMillis();
        List<MemoryEntry> factsToIndex = new ArrayList<>();
        try {
            MemoryCurator.CuratedMemory curated = curator.curate(snapshot.entries, tokenEstimator);
            ReentrantLock lock = lockFor(snapshot.sessionId);
            lock.lock();
            try {
                MemorySession session = session(snapshot.sessionId);
                Set<String> ids = new HashSet<>(snapshot.entries.stream().map(MemoryEntry::getId).toList());
                if (!session.getRecentMessages().stream().map(MemoryEntry::getId).collect(java.util.stream.Collectors.toSet())
                        .containsAll(ids)) {
                    session.setCompressionStatus("IDLE");
                    touchAndSave(session);
                    return;
                }

                String previousSummary = session.getSummaries().isEmpty() ? ""
                        : session.getSummaries().get(session.getSummaries().size() - 1).getContent();
                String mergedSummary = previousSummary.isBlank() ? curated.getSummary()
                        : previousSummary + "\n" + curated.getSummary();
                MemoryEntry summary = entry(snapshot.sessionId, MemoryType.SUMMARY, mergedSummary);
                summary.getMetadata().put("sourceVersion", String.valueOf(snapshot.version));
                session.getSummaries().clear();
                session.getSummaries().add(summary);
                session.getRecentMessages().removeIf(message -> ids.contains(message.getId()));

                for (String factContent : curated.getFacts()) {
                    if (session.getFacts().stream().noneMatch(f -> f.getContent().equalsIgnoreCase(factContent))) {
                        MemoryEntry fact = entry(snapshot.sessionId, MemoryType.FACT, factContent);
                        session.getFacts().add(fact);
                        factsToIndex.add(fact);
                    }
                }
                for (String taskContent : curated.getOpenTasks()) {
                    if (session.getOpenTasks().stream().noneMatch(t -> t.getContent().equalsIgnoreCase(taskContent))) {
                        session.getOpenTasks().add(entry(snapshot.sessionId, MemoryType.OPEN_TASK, taskContent));
                    }
                }
                session.setLastCompressionRatio(snapshot.tokensBefore == 0 ? 1.0
                        : (double) curated.getEstimatedTokens() / snapshot.tokensBefore);
                session.setLastCompressionDurationMs(System.currentTimeMillis() - started);
                session.setCompressionStatus("IDLE");
                touchAndSave(session);
                logger.info("Compressed memory session {}: {} entries, ratio={}",
                        snapshot.sessionId, snapshot.entries.size(), session.getLastCompressionRatio());
            } finally {
                lock.unlock();
            }
            factsToIndex.forEach(vectorStore::index);
        } catch (Exception e) {
            logger.error("Memory compression failed for session {}", snapshot.sessionId, e);
            ReentrantLock lock = lockFor(snapshot.sessionId);
            lock.lock();
            try {
                MemorySession session = session(snapshot.sessionId);
                session.setCompressionStatus("IDLE");
                touchAndSave(session);
            } finally {
                lock.unlock();
            }
        }
    }

    private MemorySession session(String sessionId) {
        return sessions.computeIfAbsent(sessionId, id -> memoryStore.load(id).orElseGet(() -> {
            MemorySession created = new MemorySession();
            created.setSessionId(id);
            created.setLastUpdatedAt(System.currentTimeMillis());
            return created;
        }));
    }

    private ReentrantLock lockFor(String sessionId) {
        return sessionLocks.computeIfAbsent(sessionId, id -> new ReentrantLock());
    }

    private MemoryEntry entry(String sessionId, MemoryType type, String content) {
        MemoryEntry entry = new MemoryEntry();
        entry.setId(UUID.randomUUID().toString());
        entry.setSessionId(sessionId);
        entry.setType(type);
        entry.setContent(content == null ? "" : content);
        entry.setCreatedAt(System.currentTimeMillis());
        entry.setEstimatedTokens(tokenEstimator.estimate(content));
        entry.setMetadata(new HashMap<>());
        return entry;
    }

    private List<MemoryEntry> recentMessages(List<MemoryEntry> entries, int maxCount) {
        int from = Math.max(0, entries.size() - maxCount);
        return new ArrayList<>(entries.subList(from, entries.size()));
    }

    private List<MemoryEntry> trimToBudget(List<MemoryEntry> entries, int budget) {
        List<MemoryEntry> result = new ArrayList<>();
        int used = 0;
        for (int i = entries.size() - 1; i >= 0; i--) {
            MemoryEntry entry = entries.get(i);
            if (used + entry.getEstimatedTokens() > budget) {
                break;
            }
            result.add(0, entry);
            used += entry.getEstimatedTokens();
        }
        return result;
    }

    private int totalTokens(List<MemoryEntry> entries) {
        return entries.stream().mapToInt(MemoryEntry::getEstimatedTokens).sum();
    }

    private int compressedContextTokens(MemoryContext context) {
        int tokens = tokenEstimator.estimate(context.getHistoricalSummary());
        tokens += context.getRelevantFacts().stream().mapToInt(MemoryEntry::getEstimatedTokens).sum();
        tokens += context.getOpenTasks().stream().mapToInt(MemoryEntry::getEstimatedTokens).sum();
        return tokens;
    }

    private void touchAndSave(MemorySession session) {
        session.setLastUpdatedAt(System.currentTimeMillis());
        memoryStore.save(session);
    }

    @PreDestroy
    public void shutdown() {
        compressionExecutor.shutdown();
    }

    private record CompressionSnapshot(String sessionId, long version,
                                       List<MemoryEntry> entries, int tokensBefore) {}
}
