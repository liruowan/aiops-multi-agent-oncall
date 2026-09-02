package org.example.memory;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MemoryStatus {
    private String sessionId;
    private int recentMessageCount;
    private int summaryCount;
    private int longTermFactCount;
    private int openTaskCount;
    private int estimatedTokens;
    private String compressionStatus;
    private double lastCompressionRatio;
    private long lastCompressionDurationMs;
    private int lastPromptTokens;
    private int lastPromptTokensBeforeCompression;
    private int lastPromptTokensAfterCompression;
    private double lastPromptTokenReductionRatio;
    private long lastUpdatedAt;
}
