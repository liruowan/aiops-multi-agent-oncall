package org.example.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void persistsAndLoadsSessionById() {
        MemoryProperties properties = new MemoryProperties();
        properties.setStoragePath(tempDir.toString());
        MemoryStore store = new MemoryStore(new ObjectMapper(), properties);
        MemorySession session = new MemorySession();
        session.setSessionId("session-a");
        session.setSessionVersion(3);
        session.setLastUpdatedAt(123L);

        store.save(session);

        MemorySession loaded = store.load("session-a").orElseThrow();
        assertThat(loaded.getSessionId()).isEqualTo("session-a");
        assertThat(loaded.getSessionVersion()).isEqualTo(3);
        assertThat(store.load("session-b")).isEmpty();
    }
}
