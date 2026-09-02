package org.example.memory;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class MemoryContext {
    private String historicalSummary = "";
    private List<MemoryEntry> relevantFacts = new ArrayList<>();
    private List<MemoryEntry> recentMessages = new ArrayList<>();
    private List<MemoryEntry> openTasks = new ArrayList<>();
}
