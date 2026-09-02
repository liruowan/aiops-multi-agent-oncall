param(
    [string]$OutDir = "benchmark-results",
    [string]$AgentArchitectureDir = "benchmark-results/agent-architecture-20260623-174453",
    [string]$LongContextDir = "benchmark-results/memory-token-20260624-205750",
    [string]$MemoryRetrievalDir = "benchmark-results/memory-retrieval-20260625-160521",
    [string]$ToolGovernanceStandardDir = "benchmark-results/tool-governance-20260702-111400",
    [string]$ToolGovernanceAbnormalDir = "benchmark-results/tool-governance-20260702-145941"
)

$ErrorActionPreference = "Stop"

function Read-Summary {
    param([string]$Dir)
    $path = Join-Path $Dir "summary.json"
    if (-not (Test-Path $path)) {
        throw "summary.json not found: $path"
    }
    return Get-Content -Raw -Path $path | ConvertFrom-Json
}

function Round2 {
    param([double]$Value)
    return [Math]::Round($Value, 2)
}

$agent = Read-Summary $AgentArchitectureDir
$longContext = Read-Summary $LongContextDir
$retrieval = Read-Summary $MemoryRetrievalDir
$toolStandard = Read-Summary $ToolGovernanceStandardDir
$toolAbnormal = Read-Summary $ToolGovernanceAbnormalDir

$toolTotalRuns = [int]$toolStandard.totalRuns + [int]$toolAbnormal.totalRuns
$toolPassCount = [int]$toolStandard.passCount + [int]$toolAbnormal.passCount
$toolRouteWeighted = (([double]$toolStandard.routeAccuracy * [int]$toolStandard.totalRuns) + ([double]$toolAbnormal.routeAccuracy * [int]$toolAbnormal.totalRuns)) / $toolTotalRuns
$toolResultWeighted = (([double]$toolStandard.toolResultAccuracy * [int]$toolStandard.totalRuns) + ([double]$toolAbnormal.toolResultAccuracy * [int]$toolAbnormal.totalRuns)) / $toolTotalRuns
$toolFaithfulWeighted = (([double]$toolStandard.answerFaithfulness * [int]$toolStandard.totalRuns) + ([double]$toolAbnormal.answerFaithfulness * [int]$toolAbnormal.totalRuns)) / $toolTotalRuns
$totalBenchmarkRuns = [int]$agent.totalRuns + [int]$longContext.totalRuns + [int]$retrieval.totalQueries + $toolTotalRuns
$aiOpsTask = $agent.tasks | Where-Object taskId -eq "AIOPS_MULTI_AGENT" | Select-Object -First 1

$overall = [ordered]@{
    generatedAt = (Get-Date).ToString("o")
    totalBenchmarkRuns = $totalBenchmarkRuns
    sourceDirs = [ordered]@{
        agentArchitecture = $AgentArchitectureDir
        longContext = $LongContextDir
        memoryRetrieval = $MemoryRetrievalDir
        toolGovernanceStandard = $ToolGovernanceStandardDir
        toolGovernanceAbnormal = $ToolGovernanceAbnormalDir
    }
    agentArchitecture = [ordered]@{
        totalRuns = [int]$agent.totalRuns
        passRate = [double]$agent.passRate
        avgLatencyMs = [int]$agent.avgLatencyMs
        p95LatencyMs = [int]$agent.p95LatencyMs
        aiOpsAvgLatencyMs = [int]$aiOpsTask.avgLatencyMs
    }
    longContext = [ordered]@{
        sessions = [int]$longContext.sessions
        roundsPerSession = [int]$longContext.roundsPerSession
        totalRuns = [int]$longContext.totalRuns
        compressedSessions = [int]$longContext.compressedSessions
        averagePromptTokensBeforeCompression = [int]$longContext.averagePromptTokensBeforeCompression
        averagePromptTokensAfterCompression = [int]$longContext.averagePromptTokensAfterCompression
        averagePromptTokenReductionPercent = Round2 ([double]$longContext.averagePromptTokenReductionRatio * 100.0)
        averageCompressionDurationMs = [int]$longContext.averageCompressionDurationMs
    }
    memoryRetrieval = [ordered]@{
        totalFacts = [int]$retrieval.totalFacts
        totalQueries = [int]$retrieval.totalQueries
        coreTopKHitRatePercent = [double]$retrieval.topKHitRatePercent
        exactCoreHitRatePercent = [double]$retrieval.exactCoreHitRatePercent
        irrelevantInjectionRatePercent = [double]$retrieval.irrelevantInjectionRatePercent
        baselineIrrelevantInjectionRatePercent = [double]$retrieval.baselineIrrelevantInjectionRatePercent
        irrelevantInjectionReductionRatePercent = [double]$retrieval.irrelevantInjectionReductionRatePercent
        avgReturnedFacts = [double]$retrieval.avgReturnedFacts
    }
    toolGovernance = [ordered]@{
        standardRuns = [int]$toolStandard.totalRuns
        abnormalRuns = [int]$toolAbnormal.totalRuns
        totalRuns = $toolTotalRuns
        standardPassRate = [double]$toolStandard.passRate
        abnormalPassRate = [double]$toolAbnormal.passRate
        combinedPassRate = Round2 (($toolPassCount / [double]$toolTotalRuns) * 100.0)
        routeAccuracy = Round2 $toolRouteWeighted
        toolResultAccuracy = Round2 $toolResultWeighted
        answerFaithfulness = Round2 $toolFaithfulWeighted
        abnormalFailureCategories = $toolAbnormal.failureCategories
    }
}

if (-not (Test-Path $OutDir)) {
    New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
}

$summaryPath = Join-Path $OutDir "overall-summary.json"
$reportPath = Join-Path $OutDir "overall-benchmark-report.md"
$overall | ConvertTo-Json -Depth 10 | Set-Content -Path $summaryPath -Encoding UTF8

$lines = @(
    "# OnCall Agent 总评测与审计闭环报告",
    "",
    "生成时间：$($overall.generatedAt)",
    "",
    "## 一、目标",
    "",
    "本报告汇总项目当前正式 benchmark 结果，形成可复跑、可审计、可回归对比的总评测基线。评测范围覆盖 Agent 架构、长上下文治理、结构化记忆检索、工具安全与运行治理四个方向。",
    "",
    "## 二、数据来源",
    "",
    "| 模块 | 结果目录 |",
    "| --- | --- |",
    "| Agent 架构设计 | ``$AgentArchitectureDir`` |",
    "| 长上下文治理 | ``$LongContextDir`` |",
    "| 结构化记忆检索 | ``$MemoryRetrievalDir`` |",
    "| 工具治理标准场景 | ``$ToolGovernanceStandardDir`` |",
    "| 工具治理异常场景 | ``$ToolGovernanceAbnormalDir`` |",
    "",
    "当前累计纳入总报告的标准化评测次数：``$($overall.totalBenchmarkRuns)`` 次。",
    "",
    "## 三、Agent 架构设计",
    "",
    "测试规模：``$($overall.agentArchitecture.totalRuns)`` 次。",
    "",
    "核心结果：",
    "",
    "``````text",
    "Pass rate: $($overall.agentArchitecture.passRate)%",
    "Avg latency: $($overall.agentArchitecture.avgLatencyMs) ms",
    "P95 latency: $($overall.agentArchitecture.p95LatencyMs) ms",
    "AIOps avg latency: $($overall.agentArchitecture.aiOpsAvgLatencyMs) ms",
    "``````",
    "",
    "说明：覆盖普通 Chat Agent、工具调用、SSE 流式输出和 AIOps 多 Agent 编排。",
    "",
    "## 四、长上下文治理",
    "",
    "测试规模：``$($overall.longContext.sessions)`` 个 session，每个 session ``$($overall.longContext.roundsPerSession)`` 轮，总请求 ``$($overall.longContext.totalRuns)`` 次。",
    "",
    "核心结果：",
    "",
    "``````text",
    "有效压缩 session: $($overall.longContext.compressedSessions)",
    "平均压缩前 Prompt token: $($overall.longContext.averagePromptTokensBeforeCompression)",
    "平均压缩后 Prompt token: $($overall.longContext.averagePromptTokensAfterCompression)",
    "平均 Prompt token 下降: $($overall.longContext.averagePromptTokenReductionPercent)%",
    "平均压缩耗时: $($overall.longContext.averageCompressionDurationMs) ms",
    "``````",
    "",
    "说明：验证基于轮数和 token 阈值的异步压缩机制，保留最近消息，压缩旧上下文，并按相关性注入长期事实。",
    "",
    "## 五、结构化记忆检索",
    "",
    "测试规模：``$($overall.memoryRetrieval.totalFacts)`` 条 facts，``$($overall.memoryRetrieval.totalQueries)`` 个标准查询。",
    "",
    "核心结果：",
    "",
    "``````text",
    "Core TopK hit rate: $($overall.memoryRetrieval.coreTopKHitRatePercent)%",
    "Exact core hit rate: $($overall.memoryRetrieval.exactCoreHitRatePercent)%",
    "Irrelevant injection rate: $($overall.memoryRetrieval.irrelevantInjectionRatePercent)%",
    "Baseline irrelevant injection rate: $($overall.memoryRetrieval.baselineIrrelevantInjectionRatePercent)%",
    "Irrelevant injection reduction: $($overall.memoryRetrieval.irrelevantInjectionReductionRatePercent)%",
    "Avg returned facts: $($overall.memoryRetrieval.avgReturnedFacts)",
    "``````",
    "",
    "说明：验证长期 facts 的相关性检索能力，使用严格核心事实口径统计命中率，并与全量注入基线对比无关注入下降比例。",
    "",
    "## 六、工具安全与运行治理",
    "",
    "测试规模：标准工具调用 ``$($overall.toolGovernance.standardRuns)`` 次，异常降级 ``$($overall.toolGovernance.abnormalRuns)`` 次，合计 ``$($overall.toolGovernance.totalRuns)`` 次。",
    "",
    "核心结果：",
    "",
    "``````text",
    "标准场景 Pass rate: $($overall.toolGovernance.standardPassRate)%",
    "异常场景 Pass rate: $($overall.toolGovernance.abnormalPassRate)%",
    "合并 Pass rate: $($overall.toolGovernance.combinedPassRate)%",
    "Route accuracy: $($overall.toolGovernance.routeAccuracy)%",
    "Tool result accuracy: $($overall.toolGovernance.toolResultAccuracy)%",
    "Answer faithfulness: $($overall.toolGovernance.answerFaithfulness)%",
    "``````",
    "",
    "说明：通过 ToolInvocation 审计链路记录 toolName、参数、返回摘要、耗时、成功状态和错误类型，验证工具路由、工具返回正确性、回答忠实性，以及危险操作不误调用工具。",
    "",
    "## 七、审计闭环产物",
    "",
    "当前已沉淀以下可审计产物：",
    "",
    "- 每轮明细：``runs.json``",
    "- 表格明细：``runs.csv``",
    "- 指标汇总：``summary.json``",
    "- 中文报告：``*-benchmark-report.md``",
    "- 总汇总：``overall-summary.json``",
    "- 总报告：``overall-benchmark-report.md``",
    "",
    "这些文件可以用于后续回归对比：重新跑同一脚本后，对比 pass_rate、latency、token reduction、retrieval hit rate、route accuracy、answer faithfulness 和 failure_category。",
    "",
    "## 八、简历表述建议",
    "",
    "``````text",
    "评测与审计闭环：建立覆盖 Agent 架构、长上下文压缩、结构化记忆检索和工具治理的 benchmark 体系，统一沉淀 runs.json / runs.csv / summary.json / 中文报告，支持 pass_rate、latency、Prompt token reduction、retrieval hit rate、tool route accuracy、answer faithfulness、failure_category 等指标自动汇总与回归对比；当前累计纳入 $($overall.totalBenchmarkRuns) 次标准化任务回放。",
    "``````",
    "",
    "## 九、口径限制",
    "",
    "- 工具治理标准场景基于 Mock 数据，适合验证工具链路与审计机制，不代表真实云环境稳定性。",
    "- 长上下文 token 使用项目内估算器统计，适合做相对下降比例，不等同于模型厂商精确计费 token。",
    "- 结构化记忆检索采用严格核心事实口径，命中率受测试集标注粒度影响。",
    "- Agent 架构与 AIOps 延迟受模型后端响应和本地环境影响，建议作为当前环境基线，而非绝对性能指标。"
)

$lines | Set-Content -Path $reportPath -Encoding UTF8

Write-Host "Overall benchmark report generated"
Write-Host "  $summaryPath"
Write-Host "  $reportPath"
Write-Host ""
Write-Host "Total benchmark runs: $($overall.totalBenchmarkRuns)"
Write-Host "Agent pass rate: $($overall.agentArchitecture.passRate)%"
Write-Host "Prompt token reduction: $($overall.longContext.averagePromptTokenReductionPercent)%"
Write-Host "Memory retrieval Core TopK hit rate: $($overall.memoryRetrieval.coreTopKHitRatePercent)%"
Write-Host "Tool governance combined pass rate: $($overall.toolGovernance.combinedPassRate)%"