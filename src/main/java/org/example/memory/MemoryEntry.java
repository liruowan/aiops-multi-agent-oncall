package org.example.memory;

import lombok.Getter;
import lombok.Setter;

import java.util.HashMap;
import java.util.Map;

@Getter
@Setter
public class MemoryEntry {
    private String id;
    private String sessionId;
    private MemoryType type;
    private String content;
    private long createdAt;
    private int estimatedTokens;
    private Map<String, String> metadata = new HashMap<>();
}
