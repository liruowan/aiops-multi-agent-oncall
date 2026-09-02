package org.example.agent.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

@Component
public class DateTimeTools {
    
    /** 工具名常量，用于动态构建提示词 */
    public static final String TOOL_GET_CURRENT_DATETIME = "getCurrentDateTime";
    
    private final ToolInvocationRecorder recorder;

    public DateTimeTools(ToolInvocationRecorder recorder) {
        this.recorder = recorder;
    }

    @Tool(description = "Get the current date and time in the user's timezone")
    public String getCurrentDateTime() {
        long startedAt = System.currentTimeMillis();
        String result = LocalDateTime.now().atZone(LocaleContextHolder.getTimeZone().toZoneId()).toString();
        return recorder.recordResult(TOOL_GET_CURRENT_DATETIME, Map.of(), startedAt, result);
    }
}
