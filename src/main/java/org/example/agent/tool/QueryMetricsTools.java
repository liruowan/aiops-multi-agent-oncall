package org.example.agent.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class QueryMetricsTools {

    private static final Logger logger = LoggerFactory.getLogger(QueryMetricsTools.class);
    public static final String TOOL_QUERY_PROMETHEUS_ALERTS = "queryPrometheusAlerts";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${prometheus.base-url}")
    private String prometheusBaseUrl;

    @Value("${prometheus.timeout:10}")
    private int timeout;

    @Value("${prometheus.mock-enabled:false}")
    private boolean mockEnabled;

    @Autowired
    private ToolInvocationRecorder recorder;

    private OkHttpClient httpClient;

    @jakarta.annotation.PostConstruct
    public void init() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(timeout))
                .readTimeout(Duration.ofSeconds(timeout))
                .build();
        logger.info("QueryMetricsTools initialized, prometheusBaseUrl={}, mockEnabled={}", prometheusBaseUrl, mockEnabled);
    }

    @Tool(description = "Query active alerts from Prometheus alerting system. This tool retrieves all currently active/firing alerts including labels, annotations, state, and values.")
    public String queryPrometheusAlerts() {
        long startedAt = System.currentTimeMillis();
        try {
            List<SimplifiedAlert> simplifiedAlerts;
            if (mockEnabled) {
                simplifiedAlerts = buildMockAlerts();
                logger.info("Using mock Prometheus alerts, count={}", simplifiedAlerts.size());
            } else {
                PrometheusAlertsResult result = fetchPrometheusAlerts();
                if (!"success".equals(result.getStatus())) {
                    String errorResult = buildErrorResponse("Prometheus API returned non-success status: " + result.getStatus(), result.getError());
                    return recorder.recordResult(TOOL_QUERY_PROMETHEUS_ALERTS, Map.of(), startedAt, errorResult);
                }
                simplifiedAlerts = simplifyAlerts(result);
            }

            PrometheusAlertsOutput output = new PrometheusAlertsOutput();
            output.setSuccess(true);
            output.setAlerts(simplifiedAlerts);
            output.setMessage(String.format("Retrieved %d active alerts", simplifiedAlerts.size()));

            String jsonResult = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(output);
            return recorder.recordResult(TOOL_QUERY_PROMETHEUS_ALERTS, Map.of(), startedAt, jsonResult);
        } catch (Exception e) {
            logger.error("Failed to query Prometheus alerts", e);
            String errorResult = buildErrorResponse("Query failed", e.getMessage());
            return recorder.recordException(TOOL_QUERY_PROMETHEUS_ALERTS, Map.of(), startedAt, e, errorResult);
        }
    }

    private List<SimplifiedAlert> simplifyAlerts(PrometheusAlertsResult result) {
        Set<String> seenAlertNames = new HashSet<>();
        List<SimplifiedAlert> simplifiedAlerts = new ArrayList<>();
        if (result.getData() == null || result.getData().getAlerts() == null) {
            return simplifiedAlerts;
        }
        for (PrometheusAlert alert : result.getData().getAlerts()) {
            String alertName = alert.getLabels() == null ? "unknown" : alert.getLabels().getOrDefault("alertname", "unknown");
            if (!seenAlertNames.add(alertName)) {
                continue;
            }
            SimplifiedAlert simplified = new SimplifiedAlert();
            simplified.setAlertName(alertName);
            simplified.setDescription(alert.getAnnotations() == null ? "" : alert.getAnnotations().getOrDefault("description", ""));
            simplified.setState(alert.getState());
            simplified.setActiveAt(alert.getActiveAt());
            simplified.setDuration(calculateDuration(alert.getActiveAt()));
            simplifiedAlerts.add(simplified);
        }
        return simplifiedAlerts;
    }

    private List<SimplifiedAlert> buildMockAlerts() {
        List<SimplifiedAlert> alerts = new ArrayList<>();
        Instant now = Instant.now();

        SimplifiedAlert cpuAlert = new SimplifiedAlert();
        cpuAlert.setAlertName("HighCPUUsage");
        cpuAlert.setDescription("payment-service CPU usage has stayed above 80%, current value 92%, instance pod-payment-service-7d8f9c6b5-x2k4m, namespace production");
        cpuAlert.setState("firing");
        Instant cpuActiveAt = now.minus(25, ChronoUnit.MINUTES);
        cpuAlert.setActiveAt(cpuActiveAt.toString());
        cpuAlert.setDuration(calculateDuration(cpuActiveAt.toString()));
        alerts.add(cpuAlert);

        SimplifiedAlert memoryAlert = new SimplifiedAlert();
        memoryAlert.setAlertName("HighMemoryUsage");
        memoryAlert.setDescription("order-service memory usage has stayed above 85%, current value 91%, JVM heap 3.8GB/4GB, instance pod-order-service-5c7d8e9f1-m3n2p, namespace production");
        memoryAlert.setState("firing");
        Instant memoryActiveAt = now.minus(15, ChronoUnit.MINUTES);
        memoryAlert.setActiveAt(memoryActiveAt.toString());
        memoryAlert.setDuration(calculateDuration(memoryActiveAt.toString()));
        alerts.add(memoryAlert);

        SimplifiedAlert slowAlert = new SimplifiedAlert();
        slowAlert.setAlertName("SlowResponse");
        slowAlert.setDescription("user-service P99 response time has stayed above 3 seconds, current value 4.2 seconds, affected APIs /api/v1/users/profile and /api/v1/users/orders");
        slowAlert.setState("firing");
        Instant slowActiveAt = now.minus(10, ChronoUnit.MINUTES);
        slowAlert.setActiveAt(slowActiveAt.toString());
        slowAlert.setDuration(calculateDuration(slowActiveAt.toString()));
        alerts.add(slowAlert);

        return alerts;
    }

    private PrometheusAlertsResult fetchPrometheusAlerts() throws Exception {
        String apiUrl = prometheusBaseUrl + "/api/v1/alerts";
        Request request = new Request.Builder().url(apiUrl).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new RuntimeException("HTTP request failed: " + response.code());
            }
            String responseBody = response.body().string();
            return objectMapper.readValue(responseBody, PrometheusAlertsResult.class);
        }
    }

    private String calculateDuration(String activeAtStr) {
        try {
            Instant activeAt = Instant.parse(activeAtStr);
            Duration duration = Duration.between(activeAt, Instant.now());
            long hours = duration.toHours();
            long minutes = duration.toMinutes() % 60;
            long seconds = duration.getSeconds() % 60;
            if (hours > 0) {
                return String.format("%dh%dm%ds", hours, minutes, seconds);
            }
            if (minutes > 0) {
                return String.format("%dm%ds", minutes, seconds);
            }
            return String.format("%ds", seconds);
        } catch (Exception e) {
            return "unknown";
        }
    }

    private String buildErrorResponse(String message, String error) {
        try {
            PrometheusAlertsOutput output = new PrometheusAlertsOutput();
            output.setSuccess(false);
            output.setMessage(message);
            output.setError(error);
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(output);
        } catch (Exception e) {
            return String.format("{\"success\":false,\"message\":\"%s\",\"error\":\"%s\"}", message, error);
        }
    }

    @Data
    public static class PrometheusAlert {
        private Map<String, String> labels;
        private Map<String, String> annotations;
        private String state;
        private String activeAt;
        private String value;
    }

    @Data
    public static class PrometheusAlertsResult {
        private String status;
        private AlertsData data;
        private String error;
        private String errorType;
    }

    @Data
    public static class AlertsData {
        private List<PrometheusAlert> alerts = new ArrayList<>();
    }

    @Data
    public static class SimplifiedAlert {
        @JsonProperty("alert_name")
        private String alertName;
        @JsonProperty("description")
        private String description;
        @JsonProperty("state")
        private String state;
        @JsonProperty("active_at")
        private String activeAt;
        @JsonProperty("duration")
        private String duration;
    }

    @Data
    public static class PrometheusAlertsOutput {
        @JsonProperty("success")
        private boolean success;
        @JsonProperty("alerts")
        private List<SimplifiedAlert> alerts;
        @JsonProperty("message")
        private String message;
        @JsonProperty("error")
        private String error;
    }
}