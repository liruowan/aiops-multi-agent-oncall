package org.example.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * Structured definition of a troubleshooting skill for a specific AIOps scenario.
 */
@Data
public class AiOpsSkillDefinition {

    @JsonProperty("skill_name")
    private String skillName;

    @JsonProperty("description")
    private String description;

    @JsonProperty("trigger_alerts")
    private List<String> triggerAlerts;

    @JsonProperty("metric_evidence")
    private List<String> metricEvidence;

    @JsonProperty("log_topics")
    private List<String> logTopics;

    @JsonProperty("example_log_queries")
    private List<String> exampleLogQueries;

    @JsonProperty("judgment_rules")
    private List<String> judgmentRules;

    @JsonProperty("handling_suggestions")
    private List<String> handlingSuggestions;

    @JsonProperty("output_focus")
    private List<String> outputFocus;
}
