package org.example.agent.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class QueryLogsTools {

    private static final Logger logger = LoggerFactory.getLogger(QueryLogsTools.class);
    public static final String TOOL_QUERY_LOGS = "queryLogs";
    public static final String TOOL_GET_AVAILABLE_LOG_TOPICS = "getAvailableLogTopics";
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai"));

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${cls.mock-enabled:false}")
    private boolean mockEnabled;

    @Autowired
    private ToolInvocationRecorder recorder;

    @jakarta.annotation.PostConstruct
    public void init() {
        logger.info("QueryLogsTools initialized, mockEnabled={}", mockEnabled);
    }

    @Tool(description = "Get all available log topics and their descriptions. Call this tool before querying logs when the log topic is unclear.")
    public String getAvailableLogTopics() {
        long startedAt = System.currentTimeMillis();
        try {
            List<LogTopicInfo> topics = new ArrayList<>();
            topics.add(topic("system-metrics", "System metrics logs for CPU, memory, and disk usage", List.of("cpu_usage:>80", "memory_usage:>85", "disk_usage:>90"), List.of("HighCPUUsage", "HighMemoryUsage", "HighDiskUsage")));
            topics.add(topic("application-logs", "Application error, slow request, and dependency logs", List.of("level:ERROR", "http_status:500", "response_time:>3000", "downstream OR redis OR database OR mq"), List.of("ServiceUnavailable", "SlowResponse", "HighMemoryUsage")));
            topics.add(topic("database-slow-query", "Database slow query logs", List.of("query_time:>2", "table:orders", "query_type:SELECT"), List.of("SlowResponse", "ServiceUnavailable")));
            topics.add(topic("system-events", "Kubernetes pod restart, OOMKill, and container crash events", List.of("restart OR crash", "oom_kill", "event_type:PodRestart"), List.of("ServiceUnavailable", "HighMemoryUsage")));

            LogTopicsOutput output = new LogTopicsOutput();
            output.setSuccess(true);
            output.setTopics(topics);
            output.setAvailableRegions(List.of("ap-guangzhou", "ap-shanghai", "ap-beijing", "ap-chengdu"));
            output.setDefaultRegion("ap-guangzhou");
            output.setMessage(String.format("%d log topics are available", topics.size()));
            String result = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(output);
            return recorder.recordResult(TOOL_GET_AVAILABLE_LOG_TOPICS, Map.of(), startedAt, result);
        } catch (Exception e) {
            logger.error("Failed to list log topics", e);
            String result = "{\"success\":false,\"message\":\"Failed to list log topics: " + e.getMessage() + "\"}";
            return recorder.recordException(TOOL_GET_AVAILABLE_LOG_TOPICS, Map.of(), startedAt, e, result);
        }
    }

    @Tool(description = "Query logs from Cloud Log Service (CLS). Available topics: system-metrics, application-logs, database-slow-query, system-events. Use mock mode for benchmark tests.")
    public String queryLogs(
            @ToolParam(description = "Region, for example ap-guangzhou, ap-shanghai, ap-beijing, ap-chengdu") String region,
            @ToolParam(description = "Log topic, for example system-metrics, application-logs, database-slow-query, system-events") String logTopic,
            @ToolParam(description = "Query expression, for example level:ERROR or memory_usage:>85") String query,
            @ToolParam(description = "Return limit, default 20, max 100") Integer limit) {
        long startedAt = System.currentTimeMillis();
        int actualLimit = (limit == null || limit <= 0) ? 20 : Math.min(limit, 100);
        String safeRegion = region == null || region.isBlank() ? "ap-guangzhou" : region;
        String safeTopic = logTopic == null || logTopic.isBlank() ? "system-metrics" : logTopic;
        String safeQuery = query == null ? "" : query;
        Map<String, Object> args = Map.of("region", safeRegion, "logTopic", safeTopic, "query", safeQuery, "limit", actualLimit);

        try {
            if (!mockEnabled) {
                String result = buildErrorResponse("CLS real query is not implemented. Enable mock mode for benchmark tests.");
                return recorder.recordResult(TOOL_QUERY_LOGS, args, startedAt, result);
            }

            List<LogEntry> logEntries = buildMockLogs(safeTopic, safeQuery, actualLimit);
            QueryLogsOutput output = new QueryLogsOutput();
            output.setSuccess(!logEntries.isEmpty());
            output.setRegion(safeRegion);
            output.setLogTopic(safeTopic);
            output.setQuery(safeQuery.isBlank() ? "DEFAULT_QUERY" : safeQuery);
            output.setLogs(logEntries);
            output.setTotal(logEntries.size());
            output.setMessage(logEntries.isEmpty() ? "No matching logs found" : String.format("Retrieved %d logs", logEntries.size()));
            String result = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(output);
            return recorder.recordResult(TOOL_QUERY_LOGS, args, startedAt, result);
        } catch (Exception e) {
            logger.error("Failed to query logs", e);
            String result = buildErrorResponse("Query failed: " + e.getMessage());
            return recorder.recordException(TOOL_QUERY_LOGS, args, startedAt, e, result);
        }
    }

    private LogTopicInfo topic(String name, String description, List<String> examples, List<String> relatedAlerts) {
        LogTopicInfo topic = new LogTopicInfo();
        topic.setTopicName(name);
        topic.setDescription(description);
        topic.setExampleQueries(examples);
        topic.setRelatedAlerts(relatedAlerts);
        return topic;
    }

    private List<LogEntry> buildMockLogs(String topic, String query, int limit) {
        String normalizedTopic = topic.toLowerCase();
        String normalizedQuery = query.toLowerCase();
        List<LogEntry> logs = new ArrayList<>();
        Instant now = Instant.now();

        if ("system-metrics".equals(normalizedTopic)) {
            if (normalizedQuery.contains("memory") || normalizedQuery.contains("oom") || normalizedQuery.contains(">85") || normalizedQuery.isBlank()) {
                logs.add(log(now.minus(3, ChronoUnit.MINUTES), "WARN", "order-service", "pod-order-service-5c7d8e9f1-m3n2p", "memory usage high 91.0%, JVM heap 3.8GB/4GB", Map.of("memory_usage", "91.0", "jvm_heap_used", "3.8GB", "jvm_heap_max", "4GB")));
                logs.add(log(now.minus(8, ChronoUnit.MINUTES), "WARN", "order-service", "pod-order-service-5c7d8e9f1-m3n2p", "Full GC warning: 15 Full GC events in the last 10 minutes, average 850ms", Map.of("full_gc_count", "15", "avg_gc_time_ms", "850")));
            }
            if (normalizedQuery.contains("cpu") || normalizedQuery.contains(">80")) {
                logs.add(log(now.minus(2, ChronoUnit.MINUTES), "WARN", "payment-service", "pod-payment-service-7d8f9c6b5-x2k4m", "CPU usage high 92.0%, process java, threads 245", Map.of("cpu_usage", "92.0", "process_threads", "245")));
            }
        } else if ("application-logs".equals(normalizedTopic)) {
            if (normalizedQuery.contains("error") || normalizedQuery.contains("500") || normalizedQuery.isBlank()) {
                logs.add(log(now.minus(5, ChronoUnit.MINUTES), "ERROR", "order-service", "pod-order-service-5c7d8e9f1-m3n2p", "Cannot acquire connection from pool, active: 50/50, waiting: 23", Map.of("error_type", "ConnectionPoolExhaustedException", "pool_active", "50", "waiting_threads", "23")));
                logs.add(log(now.minus(12, ChronoUnit.MINUTES), "FATAL", "order-service", "pod-order-service-5c7d8e9f1-m3n2p", "java.lang.OutOfMemoryError: Java heap space", Map.of("error_type", "OutOfMemoryError", "heap_used", "3.9GB", "heap_max", "4GB")));
            }
            if (normalizedQuery.contains("slow") || normalizedQuery.contains("response_time") || normalizedQuery.contains(">3000")) {
                logs.add(log(now.minus(4, ChronoUnit.MINUTES), "WARN", "user-service", "pod-user-service-8e9f0a1b2-k5j6h", "slow request /api/v1/users/profile, response_time 4200ms", Map.of("response_time_ms", "4200", "threshold_ms", "3000")));
            }
        } else if ("database-slow-query".equals(normalizedTopic)) {
            logs.add(log(now.minus(3, ChronoUnit.MINUTES), "WARN", "mysql", "mysql-primary-01", "slow query SELECT * FROM orders, query_time 3.2s, rows_examined 1245678", Map.of("query_time_sec", "3.2", "table", "orders", "rows_examined", "1245678")));
        } else if ("system-events".equals(normalizedTopic)) {
            logs.add(log(now.minus(15, ChronoUnit.MINUTES), "WARN", "kubernetes", "kube-controller-manager", "Pod restart event: pod-order-service-5c7d8e9f1-m3n2p, reason OOMKilled, exit_code 137", Map.of("event_type", "PodRestart", "reason", "OOMKilled", "exit_code", "137")));
        }

        if (logs.isEmpty()) {
            logs.add(log(now.minus(1, ChronoUnit.MINUTES), "INFO", "generic-service", "instance-0", "generic log message for query: " + query, new HashMap<>()));
        }
        return logs.size() > limit ? new ArrayList<>(logs.subList(0, limit)) : logs;
    }

    private LogEntry log(Instant timestamp, String level, String service, String instance, String message, Map<String, String> metrics) {
        LogEntry entry = new LogEntry();
        entry.setTimestamp(FORMATTER.format(timestamp));
        entry.setLevel(level);
        entry.setService(service);
        entry.setInstance(instance);
        entry.setMessage(message);
        entry.setMetrics(metrics);
        return entry;
    }

    private String buildErrorResponse(String message) {
        try {
            QueryLogsOutput output = new QueryLogsOutput();
            output.setSuccess(false);
            output.setMessage(message);
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(output);
        } catch (Exception e) {
            return String.format("{\"success\":false,\"message\":\"%s\"}", message);
        }
    }

    @Data
    public static class LogEntry {
        @JsonProperty("timestamp")
        private String timestamp;
        @JsonProperty("level")
        private String level;
        @JsonProperty("service")
        private String service;
        @JsonProperty("instance")
        private String instance;
        @JsonProperty("message")
        private String message;
        @JsonProperty("metrics")
        private Map<String, String> metrics;
    }

    @Data
    public static class QueryLogsOutput {
        @JsonProperty("success")
        private boolean success;
        @JsonProperty("region")
        private String region;
        @JsonProperty("log_topic")
        private String logTopic;
        @JsonProperty("query")
        private String query;
        @JsonProperty("logs")
        private List<LogEntry> logs;
        @JsonProperty("total")
        private int total;
        @JsonProperty("message")
        private String message;
    }

    @Data
    public static class LogTopicInfo {
        @JsonProperty("topic_name")
        private String topicName;
        @JsonProperty("description")
        private String description;
        @JsonProperty("example_queries")
        private List<String> exampleQueries;
        @JsonProperty("related_alerts")
        private List<String> relatedAlerts;
    }

    @Data
    public static class LogTopicsOutput {
        @JsonProperty("success")
        private boolean success;
        @JsonProperty("topics")
        private List<LogTopicInfo> topics;
        @JsonProperty("available_regions")
        private List<String> availableRegions;
        @JsonProperty("default_region")
        private String defaultRegion;
        @JsonProperty("message")
        private String message;
    }
}