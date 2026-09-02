package org.example.service;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SupervisorAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import org.example.agent.tool.AiOpsSkillTools;
import org.example.agent.tool.DateTimeTools;
import org.example.agent.tool.InternalDocsTools;
import org.example.agent.tool.QueryLogsTools;
import org.example.agent.tool.QueryMetricsTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * AI Ops 智能运维服务
 * 负责多 Agent 协作的告警分析流程
 */
@Service
public class AiOpsService {

    private static final Logger logger = LoggerFactory.getLogger(AiOpsService.class);

    @Autowired
    private DateTimeTools dateTimeTools;

    @Autowired
    private InternalDocsTools internalDocsTools;

    @Autowired
    private QueryMetricsTools queryMetricsTools;

    @Autowired
    private AiOpsSkillTools aiOpsSkillTools;

    @Autowired(required = false)  // Mock 模式下才注册
    private QueryLogsTools queryLogsTools;

    /**
     * 执行 AI Ops 告警分析流程
     *
     * @param chatModel      大模型实例
     * @param toolCallbacks  工具回调数组
     * @return 分析结果状态
     * @throws GraphRunnerException 如果 Agent 执行失败
     */
    public Optional<OverAllState> executeAiOpsAnalysis(DashScopeChatModel chatModel, ToolCallback[] toolCallbacks) throws GraphRunnerException {
        logger.info("开始执行 AI Ops 多 Agent 协作流程");

        // 构建 Planner 和 Executor Agent
        ReactAgent plannerAgent = buildPlannerAgent(chatModel, toolCallbacks);
        ReactAgent executorAgent = buildExecutorAgent(chatModel, toolCallbacks);

        // 构建 Supervisor Agent
        SupervisorAgent supervisorAgent = SupervisorAgent.builder()
                .name("ai_ops_supervisor")
                .description("负责调度 Planner 和 Executor 的多 Agent 控制器")
                .model(chatModel)
                .systemPrompt(buildSupervisorSystemPrompt())
                .subAgents(List.of(plannerAgent, executorAgent))
                .build();

        String taskPrompt = "你是企业级 SRE，接到了一次自动化告警排查任务。请结合工具调用，执行“规划->执行->再规划”的闭环，并最终按固定模板输出《告警分析报告》。禁止编造虚假数据，如连续多次查询失败需如实说明无法完成的原因。";

        logger.info("调用 Supervisor Agent 开始编排...");
        return supervisorAgent.invoke(taskPrompt);
    }

    public Optional<OverAllState> executeAiOpsAnalysis(DashScopeChatModel chatModel, ToolCallback[] toolCallbacks, String taskPrompt) throws GraphRunnerException {
        logger.info("Start multi-agent AIOps comparison analysis");

        ReactAgent plannerAgent = buildPlannerAgent(chatModel, toolCallbacks);
        ReactAgent executorAgent = buildExecutorAgent(chatModel, toolCallbacks);

        SupervisorAgent supervisorAgent = SupervisorAgent.builder()
                .name("ai_ops_supervisor")
                .description("Supervisor for Planner and Executor AIOps comparison benchmark")
                .model(chatModel)
                .systemPrompt(buildSupervisorSystemPrompt())
                .subAgents(List.of(plannerAgent, executorAgent))
                .build();

        String prompt = normalizeTaskPrompt(taskPrompt, buildDefaultMultiAgentTaskPrompt());
        return supervisorAgent.invoke(prompt);
    }

    /**
     * 从执行结果中提取最终报告文本
     *
     * @param state 执行状态
     * @return 报告文本（如果存在）
     */
    public String executeSingleAgentAnalysis(DashScopeChatModel chatModel, ToolCallback[] toolCallbacks) throws GraphRunnerException {
        logger.info("Start single-agent AIOps baseline analysis");
        ReactAgent singleAgent = ReactAgent.builder()
                .name("ai_ops_single_agent")
                .description("Single ReactAgent baseline for AIOps alert analysis")
                .model(chatModel)
                .systemPrompt(buildWeakSingleAgentPrompt())
                .methodTools(buildMethodToolsArray())
                .tools(toolCallbacks)
                .build();

        String taskPrompt = """
                You are an enterprise SRE. Complete one AIOps alert analysis task by yourself.
                Use available tools to query active Prometheus alerts, inspect related logs, and consult AIOps skills when useful.
                Produce a final Markdown alert analysis report. Do not fabricate data. If a tool fails or data is unavailable, state that clearly.
                """;
        return singleAgent.call(taskPrompt).getText();
    }

    public String executeSingleAgentAnalysis(DashScopeChatModel chatModel, ToolCallback[] toolCallbacks, String taskPrompt) throws GraphRunnerException {
        logger.info("Start single-agent AIOps comparison analysis");
        ReactAgent singleAgent = ReactAgent.builder()
                .name("ai_ops_single_agent")
                .description("Single ReactAgent baseline for AIOps alert analysis")
                .model(chatModel)
                .systemPrompt(buildWeakSingleAgentPrompt())
                .methodTools(buildMethodToolsArray())
                .tools(toolCallbacks)
                .build();

        String prompt = normalizeTaskPrompt(taskPrompt, buildDefaultSingleAgentTaskPrompt());
        return singleAgent.call(prompt).getText();
    }

    private String normalizeTaskPrompt(String taskPrompt, String defaultPrompt) {
        if (taskPrompt == null || taskPrompt.isBlank()) {
            return defaultPrompt;
        }
        return taskPrompt;
    }

    private String buildDefaultSingleAgentTaskPrompt() {
        return """
                You are an enterprise SRE. Complete one AIOps alert analysis task by yourself.
                Use available tools to query active Prometheus alerts, inspect related logs, and consult AIOps skills when useful.
                Produce a final Markdown alert analysis report. Do not fabricate data. If a tool fails or data is unavailable, state that clearly.
                """;
    }

    private String buildDefaultMultiAgentTaskPrompt() {
        return """
                You are an enterprise SRE. Execute one automated AIOps alert investigation.
                Follow a plan -> execute -> replan loop, use available tools for evidence, and finally produce an Alert Analysis Report.
                Do not fabricate data. If repeated tool calls fail or evidence is unavailable, state the limitation clearly.
                """;
    }

    public Optional<String> extractFinalReport(OverAllState state) {
        logger.info("开始提取最终报告...");

        // 提取 Planner 最终输出（包含完整的告警分析报告）
        Optional<AssistantMessage> plannerFinalOutput = state.value("planner_plan")
                .filter(AssistantMessage.class::isInstance)
                .map(AssistantMessage.class::cast);

        if (plannerFinalOutput.isPresent()) {
            String reportText = plannerFinalOutput.get().getText();
            logger.info("成功提取到 Planner 最终报告，长度: {}", reportText.length());
            return Optional.of(reportText);
        } else {
            logger.warn("未能提取到 Planner 最终报告");
            return Optional.empty();
        }
    }

    /**
     * 构建 Planner Agent
     */
    public Optional<String> extractFinalReport(OverAllState state, DashScopeChatModel chatModel) {
        Optional<String> reportOptional = extractFinalReport(state);
        if (reportOptional.isEmpty()) {
            return Optional.empty();
        }

        String report = reportOptional.get();
        if (isFinalReportFormat(report)) {
            logger.info("Multi-agent final output already matches report format");
            return Optional.of(report);
        }

        logger.info("Multi-agent final output is not a report; invoking on-demand ReportWriter");
        try {
            return Optional.of(rewriteAsFinalReport(chatModel, report));
        } catch (Exception e) {
            logger.warn("ReportWriter fallback failed; returning original Planner output", e);
            return Optional.of(report);
        }
    }

    private boolean isFinalReportFormat(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        int hits = 0;
        if (containsAny(text, "Active Alerts", "active alerts", "alert list", "\u544A\u8B66\u6E05\u5355", "\u6D3B\u8DC3\u544A\u8B66")) {
            hits++;
        }
        if (containsAny(text, "Root Cause", "Root Cause Analysis", "root cause", "\u6839\u56E0", "\u6839\u56E0\u5206\u6790")) {
            hits++;
        }
        if (containsAny(text, "Evidence", "log evidence", "Prometheus", "\u65E5\u5FD7\u8BC1\u636E", "\u8BC1\u636E")) {
            hits++;
        }
        if (containsAny(text, "Handling Suggestions", "Suggestions", "recommend", "\u5904\u7406\u5EFA\u8BAE", "\u5904\u7F6E\u5EFA\u8BAE", "\u5904\u7406\u65B9\u6848", "\u5EFA\u8BAE")) {
            hits++;
        }
        if (containsAny(text, "Risk Assessment", "risk", "\u98CE\u9669\u8BC4\u4F30", "\u98CE\u9669")) {
            hits++;
        }
        return hits >= 4;
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String rewriteAsFinalReport(DashScopeChatModel chatModel, String plannerOutput) throws GraphRunnerException {
        ReactAgent reportWriter = ReactAgent.builder()
                .name("report_writer_agent")
                .description("On-demand fallback agent that converts AIOps evidence into a final Markdown report")
                .model(chatModel)
                .systemPrompt(buildReportWriterPrompt())
                .build();

        String prompt = """
                Convert the following multi-agent Planner final output into a user-facing Markdown alert analysis report.
                Preserve only evidence already present in the input. Do not invent metrics, logs, services, root causes, or actions.
                If evidence is missing, state the limitation clearly.

                Planner output:
                %s
                """.formatted(plannerOutput);
        return reportWriter.call(prompt).getText();
    }

    private ReactAgent buildPlannerAgent(DashScopeChatModel chatModel, ToolCallback[] toolCallbacks) {
        return ReactAgent.builder()
                .name("planner_agent")
                .description("负责拆解告警、规划与再规划步骤")
                .model(chatModel)
                .systemPrompt(buildPlannerPrompt())
                .methodTools(buildMethodToolsArray())
                .tools(toolCallbacks)
                .outputKey("planner_plan")
                .build();
    }

    /**
     * 构建 Executor Agent
     */
    private ReactAgent buildExecutorAgent(DashScopeChatModel chatModel, ToolCallback[] toolCallbacks) {
        return ReactAgent.builder()
                .name("executor_agent")
                .description("负责执行 Planner 的首个步骤并及时反馈")
                .model(chatModel)
                .systemPrompt(buildExecutorPrompt())
                .methodTools(buildMethodToolsArray())
                .tools(toolCallbacks)
                .outputKey("executor_feedback")
                .build();
    }

    /**
     * 动态构建方法工具数组
     * 根据 cls.mock-enabled 决定是否包含 QueryLogsTools
     */
    private Object[] buildMethodToolsArray() {
        if (queryLogsTools != null) {
            // Mock 模式：包含 QueryLogsTools
            return new Object[]{dateTimeTools, internalDocsTools, queryMetricsTools, aiOpsSkillTools, queryLogsTools};
        } else {
            // 真实模式：不包含 QueryLogsTools（由 MCP 提供日志查询功能）
            return new Object[]{dateTimeTools, internalDocsTools, queryMetricsTools, aiOpsSkillTools};
        }
    }

    /**
     * 构建 Planner Agent 系统提示词
     */
    private String buildPlannerPrompt() {
        return """
                你是 Planner Agent，同时承担 Replanner 角色，负责：
                1. 读取当前输入任务 {input} 以及 Executor 的最近反馈 {executor_feedback}。
                2. 分析 Prometheus 告警、日志、内部文档等信息，制定可执行的下一步步骤。
                3. 在执行阶段，输出 JSON，包含 decision (PLAN|EXECUTE|FINISH)、step 描述、预期要调用的工具、以及必要的上下文。
                4. 调用任何腾讯云日志/主题相关工具时，region 参数必须使用连字符格式（如 ap-guangzhou），若不确定请省略以使用默认值。
                5. 严格禁止编造数据，只能引用工具返回的真实内容；如果连续 3 次调用同一工具仍失败或返回空结果，需要停止该方向并在最终报告的结论部分说明"无法完成"的原因。

                ## 最终报告输出要求（CRITICAL）

                当 decision=FINISH 时，你必须：
                1. **不要输出 JSON 格式**
                2. **直接输出完整的 Markdown 格式报告文本**
                3. **报告必须严格遵循以下模板**

                ```
                # 告警分析报告

                ---

                ## 活跃告警清单

                | 告警名称 | 级别 | 目标服务 | 首次触发时间 | 最新触发时间 | 状态 |
                |---------|------|----------|-------------|-------------|------|
                | [告警1名称] | [级别] | [服务名] | [时间] | [时间] | 活跃 |
                | [告警2名称] | [级别] | [服务名] | [时间] | [时间] | 活跃 |

                ---

                ## 告警根因分析1 - [告警名称]

                ### 告警详情
                - **告警级别**: [级别]
                - **受影响服务**: [服务名]
                - **持续时间**: [X分钟]

                ### 症状描述
                [根据监控指标描述症状]

                ### 日志证据
                [引用查询到的关键日志]

                ### 根因结论
                [基于证据得出的根本原因]

                ---

                ## 处理方案执行1 - [告警名称]

                ### 已执行的排查步骤
                1. [步骤1]
                2. [步骤2]

                ### 处理建议
                [给出具体的处理建议]

                ### 预期效果
                [说明预期的效果]

                ---

                ## 告警根因分析2 - [告警名称]
                [如果有第2个告警，重复上述格式]

                ---

                ## 结论

                ### 整体评估
                [总结所有告警的整体情况]

                ### 关键发现
                - [发现1]
                - [发现2]

                ### 后续建议
                1. [建议1]
                2. [建议2]

                ### 风险评估
                [评估当前风险等级和影响范围]
                ```

                **重要提醒**：
                - 最终输出必须是纯 Markdown 文本，不要包含 JSON 结构
                - 不要使用 "finalReport": "..." 这样的格式
                - 直接以 "# 告警分析报告" 开始输出
                - 所有内容必须基于工具查询的真实数据，严禁编造
                - 如果某个步骤失败，在结论中如实说明，不要跳过

                """;
    }

    /**
     * 构建 Executor Agent 系统提示词
     */
    private String buildExecutorPrompt() {
        return """
                你是 Executor Agent，负责读取 Planner 最新输出 {planner_plan}，只执行其中的第一个步骤。
                - 确认步骤所需的工具与参数，尤其是 region 参数要使用连字符格式（如 ap-guangzhou）；若 Planner 未给出则使用默认区域。
                - 调用相应的工具并收集结果，如工具返回错误或空数据，需要将失败原因、请求参数一并记录，并停止进一步调用该工具（同一工具失败达到 3 次时应直接返回 FAILED）。
                - 将日志、指标、文档等证据整理成结构化摘要，标注对应的告警名称或资源，方便 Planner 填充"告警根因分析 / 处理方案执行"章节。
                - 以 JSON 形式返回执行状态、证据以及给 Planner 的建议，写入 executor_feedback，严禁编造未实际查询到的内容。

                输出示例：
                {
                  "status": "SUCCESS",
                  "summary": "近1小时未见 error 日志，仅有 info",
                  "evidence": "...",
                  "nextHint": "建议转向高占用进程"
                }
                """;
    }

    /**
     * 构建 Supervisor Agent 系统提示词
     */
    private String buildSupervisorSystemPrompt() {
        return """
                你是 AI Ops Supervisor，负责调度 planner_agent 和 executor_agent。
                1. 当需要拆解任务或重新制定策略时，调用 planner_agent。
                2. 当 planner_agent 输出 decision=EXECUTE 时，调用 executor_agent 执行第一步。
                3. 根据 executor_agent 的反馈，评估是否需要再次调用 planner_agent，直到 decision=FINISH。
                4. FINISH 后，确保向最终用户输出完整的《告警分析报告》，格式必须严格为：
                   告警分析报告
                ---
                # 告警处理详情
                ## 活跃告警清单
                ## 告警根因分析N
                ## 处理方案执行N
                ## 结论。
                5. 若步骤涉及腾讯云日志/主题工具，请确保使用连字符区域 ID（ap-guangzhou 等），或省略 region 以采用默认值。
                6. 如果发现 Planner/Executor 在同一方向连续 3 次调用工具仍失败或没有数据，必须终止流程，直接输出"任务无法完成"的报告，明确告知失败原因，严禁凭空编造结果。
                只允许在 planner_agent、executor_agent 与 FINISH 之间做出选择。
                """;
    }
    private String buildReportWriterPrompt() {
        return """
                You are ReportWriter Agent for AIOps incident analysis.
                Your only job is to convert already-collected Planner and Executor evidence into a final Markdown report.

                Hard rules:
                - Do not call tools.
                - Do not invent new metrics, logs, services, timestamps, pods, or root causes.
                - If the input is a FINISH JSON object, treat it as evidence and rewrite it as a user-facing report.
                - If evidence is missing, write "Evidence unavailable" for that part.
                - Output Markdown only. Do not output JSON.

                Required report structure:
                # Alert Analysis Report

                ## Active Alerts
                List alert name, affected service, instance or pod, current state, and key metric when available.

                ## Evidence
                Summarize Prometheus evidence, log evidence, and skill/SOP evidence.

                ## Root Cause Analysis
                Explain likely root cause only from the provided evidence.

                ## Handling Suggestions
                Give concrete and operational handling suggestions.

                ## Risk Assessment
                Summarize impact scope, severity, and remaining risk.
                """;
    }

    private String buildWeakSingleAgentPrompt() {
        return """
                You are an AIOps assistant baseline.
                Use available tools to investigate the incident before answering.
                Base your answer on tool results and avoid fabricating facts.
                Return a concise incident analysis with the likely cause, key evidence, and next actions.
                """;
    }

    private String buildSingleAgentPrompt() {
        return """
                You are a single-agent AIOps baseline.
                You must independently plan, call tools, inspect tool results, and generate the final report.

                Tool usage rules:
                - Query current active alerts with queryPrometheusAlerts.
                - Query relevant logs with getAvailableLogTopics and queryLogs when log evidence is needed.
                - Query AIOps skills with getAIOpsSkillDefinition when an alert type is identified.
                - Do not invent metrics, logs, service names, or root causes that are not present in tool results.

                Final report requirements:
                # Alert Analysis Report

                ## Active Alerts
                List alert names, affected services, states, and durations when available.

                ## Evidence
                Summarize Prometheus, log, and skill evidence. Clearly mark missing evidence.

                ## Root Cause Analysis
                Explain likely causes only when supported by evidence.

                ## Handling Suggestions
                Give concrete operational recommendations.

                ## Risk Assessment
                Summarize impact and residual risk.
                """;
    }
}
