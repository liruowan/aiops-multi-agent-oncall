package org.example.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

@Component
public class MemoryStore {
    private static final Logger logger = LoggerFactory.getLogger(MemoryStore.class);

    private final ObjectMapper objectMapper;
    private final Path storagePath;

    public MemoryStore(ObjectMapper objectMapper, MemoryProperties properties) {
        this.objectMapper = objectMapper;
        this.storagePath = Path.of(properties.getStoragePath()).toAbsolutePath().normalize();
    }

    public Optional<MemorySession> load(String sessionId) {
        Path file = sessionFile(sessionId);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(file.toFile(), MemorySession.class));
        } catch (IOException e) {
            logger.error("Failed to load memory session {}", sessionId, e);
            return Optional.empty();
        }
    }

    public void save(MemorySession session) {
        Path temp = null;
        try {
            Files.createDirectories(storagePath);
            Path target = sessionFile(session.getSessionId());
            temp = Files.createTempFile(storagePath, target.getFileName().toString(), ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), session);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveFailure) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to persist memory session " + session.getSessionId(), e);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException cleanupFailure) {
                    logger.warn("Failed to cleanup temporary memory file {}", temp, cleanupFailure);
                }
            }
        }
    }

    private Path sessionFile(String sessionId) {
        String safeSessionId = sessionId.replaceAll("[^a-zA-Z0-9._-]", "_");
        return storagePath.resolve(safeSessionId + ".json").normalize();
    }
}
