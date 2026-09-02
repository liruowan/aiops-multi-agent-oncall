package org.example.memory;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class MemoryCurator {
    private static final Pattern SERVICE_PATTERN =
            Pattern.compile("\\b([a-zA-Z0-9][a-zA-Z0-9-]{1,60}-(?:service|server|api))\\b");
    private static final Pattern REGION_PATTERN = Pattern.compile("\\b[a-z]{2}-[a-z]+-\\d?\\b|\\bap-[a-z-]+\\b");
    private static final Pattern ALERT_PATTERN = Pattern.compile("\\b(?:High|Low|Service)[A-Z][A-Za-z0-9]+\\b");

    @Value("${dashscope.api.key:}")
    private String apiKey = "";

    @Value("${dashscope.chat.model:qwen-plus}")
    private String model = "qwen-plus";

    private final MemoryProperties properties;
    private Generation generation;

    public MemoryCurator() {
        this(new MemoryProperties());
    }

    @Autowired
    public MemoryCurator(MemoryProperties properties) {
        this.properties = properties;
    }

    public CuratedMemory curate(List<MemoryEntry> entries, TokenEstimator tokenEstimator) {
        CuratedMemory result = new CuratedMemory();
        Set<String> facts = new LinkedHashSet<>();
        Set<String> tasks = new LinkedHashSet<>();

        for (MemoryEntry entry : entries) {
            extractMatches(entry.getContent(), SERVICE_PATTERN, facts, "Service: ");
            extractMatches(entry.getContent(), REGION_PATTERN, facts, "Region: ");
            extractMatches(entry.getContent(), ALERT_PATTERN, facts, "Alert: ");

            String lower = entry.getContent().toLowerCase(Locale.ROOT);
            if (lower.contains("待办") || lower.contains("下一步") || lower.contains("需要继续")
                    || lower.contains("todo") || lower.contains("follow up")) {
                tasks.add(compact(entry.getContent(), 240));
            }
        }

        result.summary = buildSummary(entries, tokenEstimator);
        result.facts.addAll(facts);
        result.openTasks.addAll(tasks);
        result.estimatedTokens = tokenEstimator.estimate(result.summary);
        return result;
    }

    private String buildSummary(List<MemoryEntry> entries, TokenEstimator tokenEstimator) {
        if (!properties.isLlmCompressionEnabled() || apiKey == null || apiKey.isBlank()) {
            return fallbackSummary(entries);
        }
        try {
            List<List<MemoryEntry>> chunks = chunk(entries, tokenEstimator);
            List<String> chunkSummaries = new ArrayList<>();
            for (List<MemoryEntry> chunk : chunks) {
                chunkSummaries.add(callModel(mapPrompt(renderEntries(chunk))));
            }
            String summary = chunkSummaries.size() == 1
                    ? chunkSummaries.get(0)
                    : callModel(reducePrompt(String.join("\n\n", chunkSummaries)));
            return compact(summary, properties.getCompressionSummaryMaxChars());
        } catch (Exception e) {
            return fallbackSummary(entries);
        }
    }

    private List<List<MemoryEntry>> chunk(List<MemoryEntry> entries, TokenEstimator tokenEstimator) {
        List<List<MemoryEntry>> chunks = new ArrayList<>();
        int messageCount = Math.max(1, properties.getCompressionChunkMessageCount());
        for (MemoryEntry entry : entries) {
            if (chunks.isEmpty() || chunks.get(chunks.size() - 1).size() >= messageCount) {
                chunks.add(new ArrayList<>());
            }
            chunks.get(chunks.size() - 1).add(entry);
        }
        return chunks;
    }

    private String renderEntries(List<MemoryEntry> entries) {
        StringBuilder builder = new StringBuilder();
        for (MemoryEntry entry : entries) {
            String role = entry.getMetadata().getOrDefault("role", "message");
            builder.append(role)
                    .append(": ")
                    .append(compact(entry.getContent(), 1200))
                    .append("\n");
        }
        return builder.toString();
    }

    private String mapPrompt(String chunkText) {
        return """
                Summarize this OnCall conversation chunk into compact memory.
                Keep only stable diagnosis context, key evidence, decisions, and unresolved follow-ups.
                Do not invent facts. Remove repeated wording and raw logs.
                Output at most 180 Chinese characters.

                Conversation chunk:
                %s
                """.formatted(chunkText);
    }

    private String reducePrompt(String summaries) {
        return """
                Merge these OnCall memory summaries into one concise historical summary.
                Keep service names, alert names, regions, diagnosis conclusions, and unresolved follow-ups.
                Remove duplicates. Do not invent facts.
                Output at most 260 Chinese characters.

                Summaries:
                %s
                """.formatted(summaries);
    }

    private String callModel(String prompt) throws Exception {
        if (generation == null) {
            generation = new Generation();
        }
        Message userMessage = Message.builder()
                .role(Role.USER.getValue())
                .content(prompt)
                .build();
        GenerationParam param = GenerationParam.builder()
                .apiKey(apiKey)
                .model(model)
                .resultFormat("message")
                .messages(List.of(userMessage))
                .build();
        GenerationResult result = generation.call(param);
        if (result == null || result.getOutput() == null
                || result.getOutput().getChoices() == null
                || result.getOutput().getChoices().isEmpty()
                || result.getOutput().getChoices().get(0).getMessage() == null) {
            throw new IllegalStateException("empty compression model response");
        }
        String content = result.getOutput().getChoices().get(0).getMessage().getContent();
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("blank compression model response");
        }
        return content.trim();
    }

    private String fallbackSummary(List<MemoryEntry> entries) {
        StringBuilder summary = new StringBuilder();
        for (MemoryEntry entry : entries) {
            String role = entry.getMetadata().getOrDefault("role", "message");
            String compact = compact(entry.getContent(), 160);
            summary.append("- ").append(role).append(": ").append(compact).append("\n");
        }
        return compact(summary.toString().trim(), properties.getCompressionSummaryMaxChars());
    }

    private void extractMatches(String content, Pattern pattern, Set<String> target, String prefix) {
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            target.add(prefix + matcher.group());
        }
    }

    private String compact(String content, int maxLength) {
        String normalized = content == null ? "" : content.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength) + "...";
    }

    public static class CuratedMemory {
        private String summary = "";
        private final List<String> facts = new ArrayList<>();
        private final List<String> openTasks = new ArrayList<>();
        private int estimatedTokens;

        public String getSummary() { return summary; }
        public List<String> getFacts() { return facts; }
        public List<String> getOpenTasks() { return openTasks; }
        public int getEstimatedTokens() { return estimatedTokens; }
    }
}
