package org.example.memory;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "memory")
public class MemoryProperties {
    private String storagePath = "./data/memory/sessions";
    private int recentRounds = 3;
    private int compressionRoundThreshold = 8;
    private int compressionTokenThreshold = 6000;
    private int contextTokenBudget = 5000;
    private int retrievalTopK = 3;
    private double retrievalMinScore = 0.55;
    private int openTaskTopK = 3;
    private String collectionName = "memory_entries";
    private boolean llmCompressionEnabled = false;
    private int compressionChunkMessageCount = 3;
    private int compressionSummaryMaxChars = 600;
}
