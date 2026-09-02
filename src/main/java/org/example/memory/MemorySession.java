package org.example.memory;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class MemorySession {
    private String sessionId;
    private long sessionVersion;
    private List<MemoryEntry> recentMessages = new ArrayList<>();
    private List<MemoryEntry> summaries = new ArrayList<>();
    private List<MemoryEntry> facts = new ArrayList<>();
    private List<MemoryEntry> openTasks = new ArrayList<>();
    private String compressionStatus = "IDLE";
    private long lastUpdatedAt;
    private double lastCompressionRatio;
    private long lastCompressionDurationMs;
    private int lastPromptTokens;
    private int lastPromptTokensBeforeCompression;
    private int lastPromptTokensAfterCompression;
    private double lastPromptTokenReductionRatio;
    private int lastCompressedOriginalTokens;
}
