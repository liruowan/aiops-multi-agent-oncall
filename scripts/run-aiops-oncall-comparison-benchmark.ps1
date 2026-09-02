param(
    [string]$BaseUrl = "http://localhost:9900",
    [int]$RunsPerCase = 2,
    [string]$OutDir = "benchmark-results",
    [int]$TimeoutSec = 240,
    [string]$CaseFilter = "",
    [ValidateSet("both", "single", "multi")]
    [string]$Mode = "both"
)

$ErrorActionPreference = "Stop"

function New-ResultDir {
    param([string]$BaseDir)
    $timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $path = Join-Path $BaseDir "aiops-oncall-comparison-$timestamp"
    New-Item -ItemType Directory -Force -Path $path | Out-Null
    return (Resolve-Path $path).Path
}

function Percent {
    param([int]$Numerator, [int]$Denominator)
    if ($Denominator -eq 0) { return 0 }
    return [Math]::Round(($Numerator / [double]$Denominator) * 100, 2)
}

function Average {
    param([double[]]$Values)
    if ($Values.Count -eq 0) { return 0 }
    return [Math]::Round(($Values | Measure-Object -Average).Average, 2)
}

function Count-Hits {
    param([string]$Text, [string[]]$Keywords)
    return @($Keywords | Where-Object { $Text -match [regex]::Escape($_) }).Count
}

function Has-Any {
    param([string]$Text, [string[]]$Keywords)
    return (@($Keywords | Where-Object { $Text -match [regex]::Escape($_) }).Count -gt 0)
}

function Count-Evidence-Groups {
    param(
        [string]$Text,
        [object[]]$Groups
    )
    $hits = 0
    foreach ($group in $Groups) {
        $keywords = if ($group -is [string]) { $group -split "\|" } else { @($group) }
        if (Has-Any $Text $keywords) {
            $hits++
        }
    }
    return $hits
}

function U {
    param([int[]]$Codes)
    return -join ($Codes | ForEach-Object { [char]$_ })
}

function Score-Report {
    param(
        [string]$Report,
        [object[]]$ToolRecords,
        [object]$Case
    )

    $toolNames = @($ToolRecords | ForEach-Object { [string]$_.toolName })
    $prometheusUsed = $toolNames -contains "queryPrometheusAlerts"
    $logsUsed = $toolNames -contains "queryLogs"
    $skillUsed = $toolNames -contains "getAIOpsSkillDefinition"
    $topicUsed = $toolNames -contains "getAvailableLogTopics"

    $toolCoverageCount = 0
    if ($prometheusUsed) { $toolCoverageCount++ }
    if ($logsUsed) { $toolCoverageCount++ }
    if ($skillUsed) { $toolCoverageCount++ }
    if ($topicUsed) { $toolCoverageCount++ }

    $formatGroups = @(
        @("Active Alerts", "active alerts", "alert list", (U @(0x544A,0x8B66,0x6E05,0x5355)), (U @(0x6D3B,0x8DC3,0x544A,0x8B66))),
        @("Root Cause", "Root Cause Analysis", "root cause", (U @(0x6839,0x56E0)), (U @(0x6839,0x56E0,0x5206,0x6790))),
        @("Evidence", "log evidence", "Prometheus", (U @(0x65E5,0x5FD7,0x8BC1,0x636E)), (U @(0x8BC1,0x636E))),
        @("Handling Suggestions", "Suggestions", "recommend", (U @(0x5904,0x7406,0x5EFA,0x8BAE)), (U @(0x5904,0x7F6E,0x5EFA,0x8BAE)), (U @(0x5904,0x7406,0x65B9,0x6848)), (U @(0x5EFA,0x8BAE))),
        @("Risk Assessment", "risk", (U @(0x98CE,0x9669,0x8BC4,0x4F30)), (U @(0x98CE,0x9669)))
    )

    $formatHitCount = 0
    foreach ($group in $formatGroups) {
        if (Has-Any $Report $group) { $formatHitCount++ }
    }

    $alertHitCount = Count-Hits $Report $Case.expectedAlerts
    $serviceHitCount = Count-Hits $Report $Case.expectedServices
    $evidenceHitCount = Count-Hits $Report $Case.expectedEvidence
    $evidenceScore = Count-Evidence-Groups $Report $Case.evidenceGroups
    $evidenceMaxScore = @($Case.evidenceGroups).Count
    $evidenceScoreRate = if ($evidenceMaxScore -eq 0) { 0 } else { [Math]::Round(($evidenceScore / [double]$evidenceMaxScore) * 100, 2) }
    $minAlertHits = if ($null -eq $Case.minAlertHits) { 1 } else { [int]$Case.minAlertHits }
    $minServiceHits = if ($null -eq $Case.minServiceHits) { 1 } else { [int]$Case.minServiceHits }
    $minEvidenceScore = if ($null -eq $Case.minEvidenceScore) { [Math]::Min(5, $evidenceMaxScore) } else { [int]$Case.minEvidenceScore }
    $forbiddenHit = Has-Any $Report $Case.forbiddenKeywords
    $reportGenerated = -not [string]::IsNullOrWhiteSpace($Report)

    $formatScore = if ($reportGenerated) { [Math]::Round(([Math]::Min($formatHitCount, 5) / 5.0) * 20.0, 2) } else { 0 }
    $alertRecallScore = if (@($Case.expectedAlerts).Count -eq 0) { 0 } else { [Math]::Round(([Math]::Min($alertHitCount, @($Case.expectedAlerts).Count) / [double]@($Case.expectedAlerts).Count) * 15.0, 2) }
    $serviceRecallScore = if (@($Case.expectedServices).Count -eq 0) { 0 } else { [Math]::Round(([Math]::Min($serviceHitCount, @($Case.expectedServices).Count) / [double]@($Case.expectedServices).Count) * 15.0, 2) }
    $evidenceCoverageScore = if ($evidenceMaxScore -eq 0) { 0 } else { [Math]::Round(($evidenceScore / [double]$evidenceMaxScore) * 35.0, 2) }
    $toolUseScore = 0
    if ($prometheusUsed) { $toolUseScore += 4 }
    if ($logsUsed) { $toolUseScore += 4 }
    if ($skillUsed) { $toolUseScore += 1 }
    if ($topicUsed) { $toolUseScore += 1 }
    $forbiddenPenalty = if ($forbiddenHit) { 15 } else { 0 }
    $qualityScore = [Math]::Round($formatScore + $alertRecallScore + $serviceRecallScore + $evidenceCoverageScore + $toolUseScore - $forbiddenPenalty, 2)
    if ($qualityScore -lt 0) { $qualityScore = 0 }
    if ($qualityScore -gt 100) { $qualityScore = 100 }

    $formatPass = $reportGenerated -and $formatHitCount -ge 4
    $driftPass = $reportGenerated -and $alertHitCount -ge $minAlertHits -and $serviceHitCount -ge $minServiceHits -and $evidenceScore -ge $minEvidenceScore -and $prometheusUsed -and $logsUsed -and (-not $forbiddenHit)

    $categories = New-Object System.Collections.Generic.List[string]
    if (-not $reportGenerated) { $categories.Add("NO_REPORT") }
    if (-not $formatPass) { $categories.Add("FORMAT_FAIL") }
    if ($alertHitCount -lt $minAlertHits) { $categories.Add("ALERT_RECALL_LOW") }
    if ($serviceHitCount -lt $minServiceHits) { $categories.Add("SERVICE_RECALL_LOW") }
    if ($evidenceScore -lt $minEvidenceScore) { $categories.Add("EVIDENCE_SCORE_LOW") }
    if (-not $prometheusUsed) { $categories.Add("PROMETHEUS_NOT_USED") }
    if (-not $logsUsed) { $categories.Add("LOGS_NOT_USED") }
    if ($forbiddenHit) { $categories.Add("FORBIDDEN_DRIFT") }

    return [pscustomobject][ordered]@{
        reportGenerated = $reportGenerated
        formatPass = $formatPass
        driftPass = $driftPass
        pass = ($formatPass -and $driftPass)
        failureCategory = ($categories -join "|")
        formatHitCount = $formatHitCount
        alertHitCount = $alertHitCount
        serviceHitCount = $serviceHitCount
        evidenceHitCount = $evidenceHitCount
        qualityScore = $qualityScore
        formatScore = $formatScore
        alertRecallScore = $alertRecallScore
        serviceRecallScore = $serviceRecallScore
        evidenceCoverageScore = $evidenceCoverageScore
        toolUseScore = $toolUseScore
        forbiddenPenalty = $forbiddenPenalty
        evidenceScore = $evidenceScore
        evidenceMaxScore = $evidenceMaxScore
        evidenceScoreRate = $evidenceScoreRate
        minAlertHits = $minAlertHits
        minServiceHits = $minServiceHits
        minEvidenceScore = $minEvidenceScore
        toolCoverageCount = $toolCoverageCount
        prometheusUsed = $prometheusUsed
        logsUsed = $logsUsed
        skillUsed = $skillUsed
        topicUsed = $topicUsed
        forbiddenHit = $forbiddenHit
    }
}

function Invoke-AiOpsJson {
    param(
        [string]$Endpoint,
        [string]$SessionId,
        [string]$Question,
        [string]$BaseUrl,
        [object]$Case,
        [int]$TimeoutSec
    )

    try {
        Invoke-RestMethod -Uri "$BaseUrl/api/tool-invocations/$SessionId" -Method Delete -TimeoutSec 30 | Out-Null
    } catch {
    }

    $payload = @{ Id = $SessionId; Question = $Question } | ConvertTo-Json -Compress
    $start = Get-Date
    $response = $null
    $errorMessage = $null
    try {
        $response = Invoke-RestMethod -Uri "$BaseUrl$Endpoint" -Method Post -ContentType "application/json; charset=utf-8" -Body $payload -TimeoutSec $TimeoutSec
    } catch {
        $errorMessage = $_.Exception.Message
    }
    $latencyMs = [Math]::Round(((Get-Date) - $start).TotalMilliseconds, 0)

    $answer = ""
    $apiSuccess = $false
    if ($response -and $response.code -eq 200 -and $response.data) {
        $apiSuccess = [bool]$response.data.success
        if ($response.data.answer) { $answer = [string]$response.data.answer }
        if (-not $apiSuccess -and $response.data.errorMessage) { $errorMessage = [string]$response.data.errorMessage }
    }

    $recordsResponse = $null
    try {
        $recordsResponse = Invoke-RestMethod -Uri "$BaseUrl/api/tool-invocations/$SessionId" -Method Get -TimeoutSec 30
    } catch {
    }
    $records = if ($null -eq $recordsResponse) { @() } else { @($recordsResponse) }
    $score = Score-Report $answer $records $Case

    return [pscustomobject][ordered]@{
        endpoint = $Endpoint
        sessionId = $SessionId
        apiSuccess = $apiSuccess
        pass = ($apiSuccess -and $score.pass)
        formatPass = $score.formatPass
        driftPass = $score.driftPass
        failureCategory = $score.failureCategory
        latencyMs = $latencyMs
        reportGenerated = $score.reportGenerated
        formatHitCount = $score.formatHitCount
        alertHitCount = $score.alertHitCount
        serviceHitCount = $score.serviceHitCount
        evidenceHitCount = $score.evidenceHitCount
        qualityScore = $score.qualityScore
        formatScore = $score.formatScore
        alertRecallScore = $score.alertRecallScore
        serviceRecallScore = $score.serviceRecallScore
        evidenceCoverageScore = $score.evidenceCoverageScore
        toolUseScore = $score.toolUseScore
        forbiddenPenalty = $score.forbiddenPenalty
        evidenceScore = $score.evidenceScore
        evidenceMaxScore = $score.evidenceMaxScore
        evidenceScoreRate = $score.evidenceScoreRate
        minAlertHits = $score.minAlertHits
        minServiceHits = $score.minServiceHits
        minEvidenceScore = $score.minEvidenceScore
        toolCoverageCount = $score.toolCoverageCount
        prometheusUsed = $score.prometheusUsed
        logsUsed = $score.logsUsed
        skillUsed = $score.skillUsed
        topicUsed = $score.topicUsed
        forbiddenHit = $score.forbiddenHit
        toolSteps = $records.Count
        invokedTools = (@($records | ForEach-Object { $_.toolName }) -join "|")
        answerLength = $answer.Length
        error = $errorMessage
        preview = if ($answer.Length -gt 300) { $answer.Substring(0, 300) } else { $answer }
    }
}

function Build-ModeSummary {
    param([string]$Mode, [object[]]$Items)
    $modeItems = @($Items | Where-Object { $_.mode -eq $Mode })
    $formatPassCount = @($modeItems | Where-Object { $_.formatPass -eq $true }).Count
    $driftPassCount = @($modeItems | Where-Object { $_.driftPass -eq $true }).Count
    $passCount = @($modeItems | Where-Object { $_.pass -eq $true }).Count
    $failureGroups = @($modeItems | Where-Object { $_.pass -ne $true } | ForEach-Object { $_.failureCategory -split "\|" } | Where-Object { $_ } | Group-Object | ForEach-Object { [pscustomobject]@{ category = $_.Name; count = $_.Count } })

    return [pscustomobject][ordered]@{
        mode = $Mode
        runs = $modeItems.Count
        passCount = $passCount
        passRate = Percent $passCount $modeItems.Count
        reportFormatPassCount = $formatPassCount
        reportFormatPassRate = Percent $formatPassCount $modeItems.Count
        driftPassCount = $driftPassCount
        driftRate = Percent ($modeItems.Count - $driftPassCount) $modeItems.Count
        avgLatencyMs = Average @($modeItems | ForEach-Object { [double]$_.latencyMs })
        avgToolSteps = Average @($modeItems | ForEach-Object { [double]$_.toolSteps })
        avgQualityScore = Average @($modeItems | ForEach-Object { [double]$_.qualityScore })
        avgFormatScore = Average @($modeItems | ForEach-Object { [double]$_.formatScore })
        avgAlertRecallScore = Average @($modeItems | ForEach-Object { [double]$_.alertRecallScore })
        avgServiceRecallScore = Average @($modeItems | ForEach-Object { [double]$_.serviceRecallScore })
        avgEvidenceCoverageScore = Average @($modeItems | ForEach-Object { [double]$_.evidenceCoverageScore })
        avgToolUseScore = Average @($modeItems | ForEach-Object { [double]$_.toolUseScore })
        avgForbiddenPenalty = Average @($modeItems | ForEach-Object { [double]$_.forbiddenPenalty })
        avgFormatHitCount = Average @($modeItems | ForEach-Object { [double]$_.formatHitCount })
        avgEvidenceHitCount = Average @($modeItems | ForEach-Object { [double]$_.evidenceHitCount })
        avgEvidenceScore = Average @($modeItems | ForEach-Object { [double]$_.evidenceScore })
        avgEvidenceScoreRate = Average @($modeItems | ForEach-Object { [double]$_.evidenceScoreRate })
        avgToolCoverage = Average @($modeItems | ForEach-Object { [double]$_.toolCoverageCount })
        failureCategories = $failureGroups
    }
}

$cases = @(
    [pscustomobject]@{
        id="multi_alert_priority"
        name="Multi-alert priority and causal chain"
        question="Several alerts are firing at the same time. Build an incident triage report that groups HighMemoryUsage, HighCPUUsage, and SlowResponse into same-chain or independent incidents, assigns a handling priority, explains the evidence for each grouping decision, separates confirmed facts from hypotheses, and rejects Kafka/network explanations unless tool evidence supports them."
        expectedAlerts=@("HighMemoryUsage", "HighCPUUsage", "SlowResponse")
        expectedServices=@("order-service", "payment-service", "user-service")
        expectedEvidence=@("91", "92", "P99", "4.2", "Full GC", "ConnectionPoolExhaustedException", "OOMKilled")
        minAlertHits=2
        minServiceHits=2
        minEvidenceScore=6
        evidenceGroups=@(
            "HighMemoryUsage|HighCPUUsage|SlowResponse",
            "order-service|payment-service|user-service",
            "91|3.8GB|JVM heap",
            "92|CPU|threads",
            "P99|4.2|4200ms",
            "Full GC|OutOfMemoryError|OOMKilled",
            "ConnectionPoolExhaustedException|50/50",
            "Kafka|no evidence|not supported|unsupported"
        )
        forbiddenKeywords=@("DiskFull", "Kafka broker outage", "network partition", "database corrupted", "inventory-service", "checkout-service", "search-service")
    },
    [pscustomobject]@{
        id="memory_to_oom_chain"
        name="Memory/OOM cross-symptom causal chain"
        question="Build a cross-symptom fault chain for an on-call handoff. Start from order-service memory pressure, then decide whether Full GC, connection pool exhaustion, OutOfMemoryError, OOMKilled pod restart, and user-service SlowResponse are direct causes, consequences, or only correlated symptoms. Include a short event timeline, separate confirmed facts from hypotheses, list the first three remediation actions in priority order, and explicitly reject network partition or Kafka as root cause unless tool evidence supports them."
        expectedAlerts=@("HighMemoryUsage", "SlowResponse")
        expectedServices=@("order-service", "pod-order-service", "user-service")
        expectedEvidence=@("91", "3.8GB", "Full GC", "OutOfMemoryError", "OOMKilled", "exit_code 137", "ConnectionPoolExhaustedException", "50/50", "P99", "4.2")
        minAlertHits=2
        minServiceHits=2
        minEvidenceScore=8
        evidenceGroups=@(
            "HighMemoryUsage|91|3.8GB|JVM heap",
            "order-service|pod-order-service",
            "Full GC|15 Full GC|850ms",
            "OutOfMemoryError|Java heap space",
            "OOMKilled|exit_code 137|Pod restart",
            "ConnectionPoolExhaustedException|50/50",
            "SlowResponse|user-service|P99|4.2|4200ms",
            "timeline|sequence|first|then|after",
            "confirmed facts|facts|confirmed|hypothesis|hypotheses",
            "priority|first three|remediation|next actions",
            "direct cause|consequence|correlated|causal chain",
            "network|Kafka|no evidence|not supported|unsupported"
        )
        forbiddenKeywords=@("DiskFull", "Kafka broker outage", "network partition root cause", "database corrupted", "inventory-service", "checkout-service", "search-service")
    },
    [pscustomobject]@{
        id="latency_root_cause_disambiguation"
        name="Latency root-cause disambiguation"
        question="User-service P99 latency is high. Compare three competing hypotheses: user-service local issue, database slow query, and order-service memory pressure. Use a bounded investigation: query current alerts once, list log topics once, and query at most two relevant log topics. For each hypothesis, provide supporting evidence, missing evidence, and a final confidence ranking. Do not choose a root cause just because it is mentioned in the question."
        expectedAlerts=@("SlowResponse", "HighCPUUsage", "HighMemoryUsage")
        expectedServices=@("user-service", "payment-service", "order-service")
        expectedEvidence=@("P99", "4.2", "4200ms", "slow query", "3.2s", "91", "92")
        minAlertHits=2
        minServiceHits=2
        minEvidenceScore=6
        evidenceGroups=@(
            "SlowResponse|P99|4.2",
            "user-service|/api/v1/users/profile|4200ms",
            "database|slow query|3.2s|orders",
            "HighCPUUsage|payment-service|92",
            "HighMemoryUsage|order-service|91",
            "distinguish|compare|main cause|likely cause",
            "missing evidence|Evidence unavailable|insufficient evidence",
            "root cause|Root Cause"
        )
        forbiddenKeywords=@("DiskFull", "Kafka broker outage", "network partition", "database corrupted", "inventory-service", "checkout-service", "search-service")
    },
    [pscustomobject]@{
        id="service_unavailable_with_distractor"
        name="Service unavailable with distractor"
        question="Order-service is reported unavailable and an operator suspects Redis. Verify the actual evidence path using alerts and logs, determine whether connection pool exhaustion, OOM, and GC are part of one chain, separate confirmed facts from unsupported assumptions, explicitly reject Redis/Kafka if unsupported, and give a priority-ordered mitigation plan."
        expectedAlerts=@("HighMemoryUsage")
        expectedServices=@("order-service")
        expectedEvidence=@("ConnectionPoolExhaustedException", "50/50", "OutOfMemoryError", "Full GC", "91")
        minAlertHits=1
        minServiceHits=1
        minEvidenceScore=6
        evidenceGroups=@(
            "order-service|HighMemoryUsage",
            "ConnectionPoolExhaustedException|50/50|waiting",
            "OutOfMemoryError|Java heap space",
            "Full GC|memory usage high|91",
            "application-logs|ERROR|FATAL",
            "Redis|no evidence|not supported|unsupported",
            "Kafka|no evidence|not supported|unsupported",
            "priority|first|immediate"
        )
        forbiddenKeywords=@("DiskFull", "Kafka broker outage", "Redis outage root cause", "network partition", "database corrupted", "inventory-service", "checkout-service", "search-service")
    },
    [pscustomobject]@{
        id="database_slow_query_impact"
        name="Database slow query impact analysis"
        question="A database slow query may be affecting user-facing latency. Correlate database-slow-query logs with SlowResponse and related application symptoms, decide whether the slow query is primary cause, contributing factor, or unrelated coincidence, state confidence and missing evidence, and provide a remediation order."
        expectedAlerts=@("SlowResponse")
        expectedServices=@("user-service", "mysql", "orders")
        expectedEvidence=@("slow query", "3.2s", "rows_examined", "P99", "4.2", "4200ms")
        minAlertHits=1
        minServiceHits=2
        minEvidenceScore=6
        evidenceGroups=@(
            "database-slow-query|slow query|3.2s",
            "orders|rows_examined|1245678",
            "SlowResponse|P99|4.2",
            "user-service|4200ms|/api/v1/users/profile",
            "primary cause|contributing factor|correlate",
            "remediation|index|optimize|query",
            "order|priority|first",
            "missing evidence|Evidence unavailable|insufficient evidence"
        )
        forbiddenKeywords=@("DiskFull", "Kafka broker outage", "network partition", "database corrupted", "inventory-service", "checkout-service", "search-service")
    },
    [pscustomobject]@{
        id="cross_service_incident_report"
        name="Cross-service incident report"
        question="Prepare an incident report for an on-call handoff. It must separate confirmed facts from hypotheses, cover payment-service CPU, order-service memory/OOM, user-service latency, explain whether they form one incident or multiple incidents, list next actions in priority order, and avoid inventing root causes for services without evidence."
        expectedAlerts=@("HighCPUUsage", "HighMemoryUsage", "SlowResponse")
        expectedServices=@("payment-service", "order-service", "user-service")
        expectedEvidence=@("92", "91", "3.8GB", "Full GC", "OOMKilled", "4.2", "4200ms")
        minAlertHits=2
        minServiceHits=2
        minEvidenceScore=6
        evidenceGroups=@(
            "confirmed facts|facts|confirmed",
            "hypothesis|hypotheses|unknown|missing evidence",
            "payment-service|HighCPUUsage|92",
            "order-service|HighMemoryUsage|3.8GB|OOMKilled",
            "user-service|SlowResponse|P99|4.2",
            "priority|next actions|handoff",
            "Risk Assessment|risk",
            "do not invent|no evidence|unsupported|Evidence unavailable"
        )
        forbiddenKeywords=@("DiskFull", "Kafka broker outage", "network partition", "database corrupted", "inventory-service", "checkout-service", "search-service")
    }
)

$resultDir = New-ResultDir $OutDir
$flatResults = New-Object System.Collections.Generic.List[object]
$selectedCases = if ([string]::IsNullOrWhiteSpace($CaseFilter)) {
    @($cases)
} else {
    @($cases | Where-Object { $_.id -eq $CaseFilter -or $_.name -like "*$CaseFilter*" })
}

if ($selectedCases.Count -eq 0) {
    throw "No benchmark case matched CaseFilter='$CaseFilter'"
}

Write-Host "AIOps OnCall architecture comparison output: $resultDir"
Write-Host "BaseUrl: $BaseUrl"
Write-Host "Cases: $($selectedCases.Count)"
Write-Host "RunsPerCase: $RunsPerCase"
Write-Host "Mode: $Mode"
Write-Host "TimeoutSec: $TimeoutSec"

for ($round = 1; $round -le $RunsPerCase; $round++) {
    foreach ($case in $selectedCases) {
        Write-Host "Running $($case.id) [$round/$RunsPerCase]..."
        $timestamp = Get-Date -Format yyyyMMddHHmmss
        $singleSessionId = "oncall-single-$($case.id)-$round-$timestamp-$(Get-Random)"
        $multiSessionId = "oncall-multi-$($case.id)-$round-$timestamp-$(Get-Random)"

        $items = New-Object System.Collections.Generic.List[object]
        if ($Mode -eq "both" -or $Mode -eq "single") {
            Write-Host "  calling single agent..."
            $single = Invoke-AiOpsJson "/api/ai_ops_single" $singleSessionId $case.question $BaseUrl $case $TimeoutSec
            $items.Add(@{ mode="single"; data=$single }) | Out-Null
        }
        if ($Mode -eq "both" -or $Mode -eq "multi") {
            Write-Host "  calling multi agent..."
            $multi = Invoke-AiOpsJson "/api/ai_ops_multi" $multiSessionId $case.question $BaseUrl $case $TimeoutSec
            $items.Add(@{ mode="multi"; data=$multi }) | Out-Null
        }

        foreach ($item in $items) {
            $d = $item.data
            $flatResults.Add([pscustomobject][ordered]@{
                round = $round
                caseId = $case.id
                caseName = $case.name
                mode = $item.mode
                endpoint = $d.endpoint
                sessionId = $d.sessionId
                apiSuccess = $d.apiSuccess
                pass = $d.pass
                formatPass = $d.formatPass
                driftPass = $d.driftPass
                failureCategory = $d.failureCategory
                latencyMs = $d.latencyMs
                reportGenerated = $d.reportGenerated
                formatHitCount = $d.formatHitCount
                alertHitCount = $d.alertHitCount
                serviceHitCount = $d.serviceHitCount
                evidenceHitCount = $d.evidenceHitCount
                qualityScore = $d.qualityScore
                formatScore = $d.formatScore
                alertRecallScore = $d.alertRecallScore
                serviceRecallScore = $d.serviceRecallScore
                evidenceCoverageScore = $d.evidenceCoverageScore
                toolUseScore = $d.toolUseScore
                forbiddenPenalty = $d.forbiddenPenalty
                evidenceScore = $d.evidenceScore
                evidenceMaxScore = $d.evidenceMaxScore
                evidenceScoreRate = $d.evidenceScoreRate
                minAlertHits = $d.minAlertHits
                minServiceHits = $d.minServiceHits
                minEvidenceScore = $d.minEvidenceScore
                toolCoverageCount = $d.toolCoverageCount
                prometheusUsed = $d.prometheusUsed
                logsUsed = $d.logsUsed
                skillUsed = $d.skillUsed
                topicUsed = $d.topicUsed
                forbiddenHit = $d.forbiddenHit
                toolSteps = $d.toolSteps
                invokedTools = $d.invokedTools
                answerLength = $d.answerLength
                error = $d.error
                preview = $d.preview
            }) | Out-Null
        }

        foreach ($item in $items) {
            $d = $item.data
            Write-Host "  $($item.mode) qualityScore=$($d.qualityScore) formatScore=$($d.formatScore) evidenceScore=$($d.evidenceScore)/$($d.evidenceMaxScore) drift=$($d.driftPass) tool_steps=$($d.toolSteps) latencyMs=$($d.latencyMs)"
        }
    }
}

$singleSummary = Build-ModeSummary "single" $flatResults
$multiSummary = Build-ModeSummary "multi" $flatResults
$summary = [ordered]@{
    generatedAt = (Get-Date).ToString("o")
    baseUrl = $BaseUrl
    cases = $selectedCases.Count
    runsPerCase = $RunsPerCase
    totalReports = $flatResults.Count
    scoring = [ordered]@{
        qualityScoreRule = "0-100 score = formatScore(20) + alertRecallScore(15) + serviceRecallScore(15) + evidenceCoverageScore(35) + toolUseScore(10) - forbiddenPenalty(15)."
        reportFormatPassRule = "Report is non-empty and matches at least 4 of 5 sections: active alerts, root cause, evidence, handling suggestions, risk assessment."
        driftPassRule = "Report must satisfy each case's minAlertHits, minServiceHits, minEvidenceScore, use Prometheus and logs, and avoid forbidden off-topic keywords."
        evidenceScoreRule = "Each case has 8 evidence groups. A report gets 1 point per group when it mentions at least one keyword in that group."
        passRule = "Debug-only binary flag: apiSuccess AND reportFormatPass AND driftPass. Use qualityScore as the primary comparison metric."
    }
    single = $singleSummary
    multi = $multiSummary
    improvements = [ordered]@{
        passRateDelta = [Math]::Round([double]$multiSummary.passRate - [double]$singleSummary.passRate, 2)
        avgQualityScoreDelta = [Math]::Round([double]$multiSummary.avgQualityScore - [double]$singleSummary.avgQualityScore, 2)
        avgFormatScoreDelta = [Math]::Round([double]$multiSummary.avgFormatScore - [double]$singleSummary.avgFormatScore, 2)
        avgAlertRecallScoreDelta = [Math]::Round([double]$multiSummary.avgAlertRecallScore - [double]$singleSummary.avgAlertRecallScore, 2)
        avgServiceRecallScoreDelta = [Math]::Round([double]$multiSummary.avgServiceRecallScore - [double]$singleSummary.avgServiceRecallScore, 2)
        avgEvidenceCoverageScoreDelta = [Math]::Round([double]$multiSummary.avgEvidenceCoverageScore - [double]$singleSummary.avgEvidenceCoverageScore, 2)
        reportFormatPassRateDelta = [Math]::Round([double]$multiSummary.reportFormatPassRate - [double]$singleSummary.reportFormatPassRate, 2)
        driftRateDelta = [Math]::Round([double]$multiSummary.driftRate - [double]$singleSummary.driftRate, 2)
        avgToolStepsDelta = [Math]::Round([double]$multiSummary.avgToolSteps - [double]$singleSummary.avgToolSteps, 2)
        avgEvidenceHitCountDelta = [Math]::Round([double]$multiSummary.avgEvidenceHitCount - [double]$singleSummary.avgEvidenceHitCount, 2)
        avgEvidenceScoreDelta = [Math]::Round([double]$multiSummary.avgEvidenceScore - [double]$singleSummary.avgEvidenceScore, 2)
        avgEvidenceScoreRateDelta = [Math]::Round([double]$multiSummary.avgEvidenceScoreRate - [double]$singleSummary.avgEvidenceScoreRate, 2)
        latencyDeltaMs = [Math]::Round([double]$multiSummary.avgLatencyMs - [double]$singleSummary.avgLatencyMs, 0)
    }
}

$runsJson = Join-Path $resultDir "runs.json"
$summaryJson = Join-Path $resultDir "summary.json"
$runsCsv = Join-Path $resultDir "runs.csv"

$flatResults | ConvertTo-Json -Depth 8 | Set-Content -Path $runsJson -Encoding UTF8
$summary | ConvertTo-Json -Depth 8 | Set-Content -Path $summaryJson -Encoding UTF8
$flatResults | Export-Csv -Path $runsCsv -NoTypeInformation -Encoding UTF8

Write-Host ""
Write-Host "AIOps OnCall architecture comparison complete"
Write-Host "  Single report format pass rate: $($singleSummary.reportFormatPassRate)%"
Write-Host "  Multi report format pass rate: $($multiSummary.reportFormatPassRate)%"
Write-Host "  Single drift rate: $($singleSummary.driftRate)%"
Write-Host "  Multi drift rate: $($multiSummary.driftRate)%"
Write-Host "  Single avg quality score: $($singleSummary.avgQualityScore)/100"
Write-Host "  Multi avg quality score: $($multiSummary.avgQualityScore)/100"
Write-Host "  Single avg tool_steps: $($singleSummary.avgToolSteps)"
Write-Host "  Multi avg tool_steps: $($multiSummary.avgToolSteps)"
Write-Host "  Single avg evidence score: $($singleSummary.avgEvidenceScore)/$($flatResults | Where-Object { $_.mode -eq 'single' } | Select-Object -First 1 -ExpandProperty evidenceMaxScore)"
Write-Host "  Multi avg evidence score: $($multiSummary.avgEvidenceScore)/$($flatResults | Where-Object { $_.mode -eq 'multi' } | Select-Object -First 1 -ExpandProperty evidenceMaxScore)"
Write-Host ""
Write-Host "Wrote:"
Write-Host "  $runsJson"
Write-Host "  $summaryJson"
Write-Host "  $runsCsv"
