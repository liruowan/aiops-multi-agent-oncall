package org.example.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class ToolInvocationRecorder {

    private static final String UNKNOWN_SESSION = "__unknown__";
    private static final int RESULT_PREVIEW_LIMIT = 4000;

    private final ThreadLocal<String> currentSessionId = new ThreadLocal<>();
    private final ConcurrentMap<String, List<ToolInvocationRecord>> recordsBySession = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public void setCurrentSessionId(String sessionId) {
        currentSessionId.set(sessionId == null || sessionId.isBlank() ? UNKNOWN_SESSION : sessionId);
    }

    public void clearCurrentSessionId() {
        currentSessionId.remove();
    }

    public String recordResult(String toolName, Map<String, Object> arguments, long startedAtMs, String result) {
        ToolInvocationRecord record = new ToolInvocationRecord();
        record.setId(UUID.randomUUID().toString());
        record.setSessionId(currentSessionId.get() == null ? UNKNOWN_SESSION : currentSessionId.get());
        record.setToolName(toolName);
        record.setArguments(arguments == null ? Collections.emptyMap() : arguments);
        record.setDurationMs(Math.max(0, System.currentTimeMillis() - startedAtMs));
        record.setCreatedAt(System.currentTimeMillis());
        record.setResultPreview(preview(result));
        record.setSuccess(inferSuccess(result));
        record.setErrorMessage(record.isSuccess() ? null : inferError(result));

        recordsBySession
                .computeIfAbsent(record.getSessionId(), key -> Collections.synchronizedList(new ArrayList<>()))
                .add(record);
        return result;
    }

    public String recordException(String toolName, Map<String, Object> arguments, long startedAtMs, Exception exception, String fallbackResult) {
        ToolInvocationRecord record = new ToolInvocationRecord();
        record.setId(UUID.randomUUID().toString());
        record.setSessionId(currentSessionId.get() == null ? UNKNOWN_SESSION : currentSessionId.get());
        record.setToolName(toolName);
        record.setArguments(arguments == null ? Collections.emptyMap() : arguments);
        record.setDurationMs(Math.max(0, System.currentTimeMillis() - startedAtMs));
        record.setCreatedAt(System.currentTimeMillis());
        record.setResultPreview(preview(fallbackResult));
        record.setSuccess(false);
        record.setErrorMessage(exception.getMessage());

        recordsBySession
                .computeIfAbsent(record.getSessionId(), key -> Collections.synchronizedList(new ArrayList<>()))
                .add(record);
        return fallbackResult;
    }

    public List<ToolInvocationRecord> getRecords(String sessionId) {
        List<ToolInvocationRecord> records = recordsBySession.get(sessionId);
        if (records == null) {
            return List.of();
        }
        synchronized (records) {
            return new ArrayList<>(records);
        }
    }

    public void clearSession(String sessionId) {
        recordsBySession.remove(sessionId);
    }

    private String preview(String result) {
        if (result == null) {
            return "";
        }
        return result.length() <= RESULT_PREVIEW_LIMIT ? result : result.substring(0, RESULT_PREVIEW_LIMIT);
    }

    private boolean inferSuccess(String result) {
        if (result == null || result.isBlank()) {
            return false;
        }
        try {
            JsonNode node = objectMapper.readTree(result);
            if (node.has("success")) {
                return node.get("success").asBoolean(false);
            }
            if (node.has("status")) {
                String status = node.get("status").asText("");
                return !status.equalsIgnoreCase("error") && !status.equalsIgnoreCase("no_results");
            }
            return true;
        } catch (Exception ignored) {
            String lower = result.toLowerCase();
            return !lower.contains("\"success\":false") && !lower.contains("\"status\":\"error\"");
        }
    }

    private String inferError(String result) {
        if (result == null || result.isBlank()) {
            return "empty result";
        }
        try {
            JsonNode node = objectMapper.readTree(result);
            if (node.has("error")) {
                return node.get("error").asText();
            }
            if (node.has("message")) {
                return node.get("message").asText();
            }
        } catch (Exception ignored) {
            return result.length() > 300 ? result.substring(0, 300) : result;
        }
        return null;
    }
}
