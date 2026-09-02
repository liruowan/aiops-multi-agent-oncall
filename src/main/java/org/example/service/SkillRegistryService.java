package org.example.service;

import org.example.dto.AiOpsSkillDefinition;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Minimal in-memory registry for AIOps skills.
 */
@Service
public class SkillRegistryService {

    private final Map<String, AiOpsSkillDefinition> skillsByName = new LinkedHashMap<>();
    private final Map<String, String> skillNameByAlert = new LinkedHashMap<>();

    public SkillRegistryService() {
        register(buildCpuHighUsageSkill());
    }

    public List<AiOpsSkillDefinition> listSkills() {
        return new ArrayList<>(skillsByName.values());
    }

    public Optional<AiOpsSkillDefinition> getSkillByName(String skillName) {
        if (skillName == null || skillName.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(skillsByName.get(normalize(skillName)));
    }

    public Optional<AiOpsSkillDefinition> getSkillByAlertName(String alertName) {
        if (alertName == null || alertName.isBlank()) {
            return Optional.empty();
        }
        String skillKey = skillNameByAlert.get(normalize(alertName));
        return skillKey == null ? Optional.empty() : Optional.ofNullable(skillsByName.get(skillKey));
    }

    public Optional<AiOpsSkillDefinition> getSkillByNameOrAlert(String identifier) {
        return getSkillByName(identifier).or(() -> getSkillByAlertName(identifier));
    }

    private void register(AiOpsSkillDefinition skill) {
        String normalizedSkillName = normalize(skill.getSkillName());
        skillsByName.put(normalizedSkillName, skill);
        for (String alertName : skill.getTriggerAlerts()) {
            skillNameByAlert.put(normalize(alertName), normalizedSkillName);
        }
    }

    private String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private AiOpsSkillDefinition buildCpuHighUsageSkill() {
        AiOpsSkillDefinition skill = new AiOpsSkillDefinition();
        skill.setSkillName("CPUHighUsageSkill");
        skill.setDescription("Troubleshoot sustained CPU high usage alerts by combining alert status, metrics evidence, application evidence, and SOP guidance.");
        skill.setTriggerAlerts(List.of("HighCPUUsage"));
        skill.setMetricEvidence(List.of(
                "Check whether HighCPUUsage is still firing",
                "Check alert duration",
                "Check the impacted service or instance"
        ));
        skill.setLogTopics(List.of("system-metrics", "application-logs"));
        skill.setExampleLogQueries(List.of(
                "cpu_usage:>80",
                "level:ERROR",
                "response_time:>3000",
                "downstream OR redis OR database OR mq"
        ));
        skill.setJudgmentRules(List.of(
                "If CPU stays high and slow requests increase, suspect hotspot traffic or inefficient processing",
                "If CPU high is accompanied by downstream timeout logs, check dependency pressure first",
                "If evidence is insufficient, keep the conclusion conservative and explicitly state the evidence gap"
        ));
        skill.setHandlingSuggestions(List.of(
                "Check hotspot traffic and expensive requests",
                "Check downstream timeout and retry pressure",
                "Consider scaling, rate limiting, or SQL optimization based on evidence"
        ));
        skill.setOutputFocus(List.of(
                "alert details",
                "symptom description",
                "key log evidence",
                "root cause conclusion",
                "handling suggestions"
        ));
        return skill;
    }
}
