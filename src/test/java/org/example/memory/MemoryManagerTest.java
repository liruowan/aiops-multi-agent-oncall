package org.example.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MemoryManagerTest {
    @TempDir
    Path tempDir;

    private MemoryManager manager;

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.shutdown();
        }
    }

    @Test
    void asynchronouslyCompressesOldMessagesAndKeepsRecentRound() throws Exception {
        manager = manager(tempDir, 1, 1);
        manager.addUserMessage("s1", "排查 order-service");
        manager.addAssistantMessage("s1", "先查看告警");
        manager.addUserMessage("s1", "下一步检查 HighMemoryUsage");
        manager.addAssistantMessage("s1", "继续检查 ap-guangzhou 日志");

        manager.scheduleCompressionIfNeeded("s1");
        waitUntilIdle(manager, "s1");

        MemoryStatus status = manager.getStatus("s1");
        assertThat(status.getRecentMessageCount()).isEqualTo(2);
        assertThat(status.getSummaryCount()).isEqualTo(1);
        assertThat(status.getLongTermFactCount()).isGreaterThan(0);

        manager.clearConversation("s1");
        MemoryStatus cleared = manager.getStatus("s1");
        assertThat(cleared.getRecentMessageCount()).isZero();
        assertThat(cleared.getSummaryCount()).isZero();
        assertThat(cleared.getLongTermFactCount()).isEqualTo(status.getLongTermFactCount());
    }

    @Test
    void restoresSameSessionAndKeepsSessionsIsolated() {
        manager = manager(tempDir, 8, 3);
        manager.addUserMessage("s1", "session one");
        manager.addAssistantMessage("s1", "answer one");
        manager.addUserMessage("s2", "session two");

        MemoryManager restarted = manager(tempDir, 8, 3);
        try {
            MemoryContext s1 = restarted.buildContextForQuery("s1", "new question");
            MemoryContext s2 = restarted.buildContextForQuery("s2", "new question");

            assertThat(s1.getRecentMessages()).extracting(MemoryEntry::getContent)
                    .containsExactly("session one", "answer one");
            assertThat(s2.getRecentMessages()).extracting(MemoryEntry::getContent)
                    .containsExactly("session two");
        } finally {
            restarted.shutdown();
        }
    }

    private MemoryManager manager(Path path, int compressionRounds, int recentRounds) {
        MemoryProperties properties = new MemoryProperties();
        properties.setStoragePath(path.toString());
        properties.setCompressionRoundThreshold(compressionRounds);
        properties.setCompressionTokenThreshold(100000);
        properties.setRecentRounds(recentRounds);
        MemoryStore store = new MemoryStore(new ObjectMapper(), properties);
        MemoryVectorStore vectorStore = mock(MemoryVectorStore.class);
        when(vectorStore.search(anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of());
        MemoryRetriever retriever = mock(MemoryRetriever.class);
        when(retriever.retrieve(anyString(), anyString(), anyList())).thenReturn(List.of());
        return new MemoryManager(store, retriever, vectorStore, new MemoryCurator(),
                new TokenEstimator(), properties);
    }

    private void waitUntilIdle(MemoryManager target, String sessionId) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (Instant.now().isBefore(deadline)) {
            MemoryStatus status = target.getStatus(sessionId);
            if ("IDLE".equals(status.getCompressionStatus()) && status.getSummaryCount() > 0) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Compression did not complete");
    }
}
