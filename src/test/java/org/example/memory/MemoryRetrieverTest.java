package org.example.memory;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MemoryRetrieverTest {
    @Test
    void fallsBackToRelevantKeywordFactsAndDoesNotForceTopK() {
        MemoryProperties properties = new MemoryProperties();
        properties.setRetrievalMinScore(0.45);
        properties.setRetrievalTopK(5);
        MemoryVectorStore vectorStore = mock(MemoryVectorStore.class);
        when(vectorStore.search("s1", "order-service memory", 15)).thenReturn(List.of());
        MemoryRetriever retriever = new MemoryRetriever(vectorStore, properties);

        MemoryEntry relevant = fact("1", "s1", "Service: order-service");
        MemoryEntry irrelevant = fact("2", "s1", "Region: ap-guangzhou");

        List<MemoryEntry> result = retriever.retrieve(
                "s1", "order-service memory", List.of(relevant, irrelevant));

        assertThat(result).extracting(MemoryEntry::getId).containsExactly("1");
    }

    private MemoryEntry fact(String id, String sessionId, String content) {
        MemoryEntry entry = new MemoryEntry();
        entry.setId(id);
        entry.setSessionId(sessionId);
        entry.setType(MemoryType.FACT);
        entry.setContent(content);
        entry.setCreatedAt(System.currentTimeMillis());
        entry.setMetadata(Map.of());
        return entry;
    }
}
