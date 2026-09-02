package org.example.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.dto.AiOpsSkillDefinition;
import org.example.service.SkillRegistryService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Tool entrypoint for querying scenario-based AIOps skills.
 */
@Component
public class AiOpsSkillTools {

    private final SkillRegistryService skillRegistryService;
    private final ToolInvocationRecorder recorder;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AiOpsSkillTools(SkillRegistryService skillRegistryService, ToolInvocationRecorder recorder) {
        this.skillRegistryService = skillRegistryService;
        this.recorder = recorder;
    }

    @Tool(description = "List all built-in AIOps troubleshooting skills.")
    public String listAIOpsSkills() {
        long startedAt = System.currentTimeMillis();
        try {
            List<AiOpsSkillDefinition> skills = skillRegistryService.listSkills();
            String result = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                    "success", true,
                    "count", skills.size(),
                    "skills", skills
            ));
            return recorder.recordResult("listAIOpsSkills", Map.of(), startedAt, result);
        } catch (Exception e) {
            String result = "{\"success\":false,\"message\":\"Failed to list AIOps skills: " + e.getMessage() + "\"}";
            return recorder.recordException("listAIOpsSkills", Map.of(), startedAt, e, result);
        }
    }

    @Tool(description = "Get a structured AIOps skill definition by skill name or alert name. " +
            "Example identifiers: CPUHighUsageSkill or HighCPUUsage.")
    public String getAIOpsSkillDefinition(
            @ToolParam(description = "Skill name or alert name")
            String identifier) {
        long startedAt = System.currentTimeMillis();
        try {
            String result = skillRegistryService.getSkillByNameOrAlert(identifier)
                    .map(this::serializeSkill)
                    .orElse("{\"success\":false,\"message\":\"No matching skill found\"}");
            return recorder.recordResult("getAIOpsSkillDefinition", Map.of("identifier", String.valueOf(identifier)), startedAt, result);
        } catch (Exception e) {
            String result = "{\"success\":false,\"message\":\"Failed to get AIOps skill definition: " + e.getMessage() + "\"}";
            return recorder.recordException("getAIOpsSkillDefinition", Map.of("identifier", String.valueOf(identifier)), startedAt, e, result);
        }
    }

    private String serializeSkill(AiOpsSkillDefinition skillDefinition) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                    "success", true,
                    "skill", skillDefinition
            ));
        } catch (Exception e) {
            return "{\"success\":false,\"message\":\"Failed to serialize AIOps skill definition\"}";
        }
    }
}
