package org.example.agent.tool;

import lombok.Getter;
import lombok.Setter;

import java.util.Map;

@Getter
@Setter
public class ToolInvocationRecord {
    private String id;
    private String sessionId;
    private String toolName;
    private Map<String, Object> arguments;
    private boolean success;
    private long durationMs;
    private String resultPreview;
    private String errorMessage;
    private long createdAt;
}
