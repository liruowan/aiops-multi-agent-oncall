param(
    [string]$BaseUrl = "http://localhost:9900",
    [int]$Runs = 5,
    [string]$OutDir = "benchmark-results"
)

$ErrorActionPreference = "Stop"

function New-ResultDir {
    param([string]$BaseDir)
    $timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $path = Join-Path $BaseDir "aiops-architecture-comparison-$timestamp"
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

function Contains-All {
    param([string]$Text, [string[]]$Keywords)
    foreach ($keyword in $Keywords) {
        if ($Text -notmatch [regex]::Escape($keyword)) {
            return $false
        }
    }
    return $true
}

function Score-Report {
    param(
        [string]$Report,
        [object[]]$ToolRecords
    )

    $requiredAlertKeywords = @("HighCPUUsage", "HighMemoryUsage", "SlowResponse")
    $qualityGroups = @(
        @("OOMKilled", "OutOfMemoryError", "Full GC", "ConnectionPoolExhaustedException", "slow query", "P99"),
        @("order-service", "payment-service", "user-service"),
        @("CPU", "memory", "latency", "response"),
        @("recommend", "suggest", "处理", "建议", "扩容", "检查")
    )
    $toolNames = @($ToolRecords | ForEach-Object { [string]$_.toolName })

    $alertHitCount = @($requiredAlertKeywords | Where-Object { $Report -match [regex]::Escape($_) }).Count
    $qualityHitCount = 0
    foreach ($group in $qualityGroups) {
        foreach ($keyword in $group) {
            if ($Report -match [regex]::Escape($keyword)) {
                $qualityHitCount++
                break
            }
        }
    }

    $prometheusUsed = $toolNames -contains "queryPrometheusAlerts"
    $logsUsed = $toolNames -contains "queryLogs"
    $skillUsed = $toolNames -contains "getAIOpsSkillDefinition"
    $topicUsed = $toolNames -contains "getAvailableLogTopics"

    $toolCoverageCount = 0
    if ($prometheusUsed) { $toolCoverageCount++ }
    if ($logsUsed) { $toolCoverageCount++ }
    if ($skillUsed) { $toolCoverageCount++ }
    if ($topicUsed) { $toolCoverageCount++ }

    $forbidden = @("fabricated", "I assume", "cannot access Prometheus", "no tool available")
    $forbiddenHit = @($forbidden | Where-Object { $Report -match [regex]::Escape($_) }).Count -gt 0

    $reportGenerated = -not [string]::IsNullOrWhiteSpace($Report)
    $pass = $reportGenerated -and $alertHitCount -ge 2 -and $qualityHitCount -ge 2 -and $toolCoverageCount -ge 2 -and (-not $forbiddenHit)

    return [pscustomobject]@{
        pass = $pass
        reportGenerated = $reportGenerated
        alertHitCount = $alertHitCount
        qualityHitCount = $qualityHitCount
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
        [string]$BaseUrl
    )

    try {
        Invoke-RestMethod -Uri "$BaseUrl/api/tool-invocations/$SessionId" -Method Delete -TimeoutSec 30 | Out-Null
    } catch {
    }

    $payload = @{ Id = $SessionId; Question = "AIOps architecture comparison" } | ConvertTo-Json -Compress
    $start = Get-Date
    $response = $null
    $errorMessage = $null
    try {
        $response = Invoke-RestMethod `
            -Uri "$BaseUrl$Endpoint" `
            -Method Post `
            -ContentType "application/json; charset=utf-8" `
            -Body $payload `
            -TimeoutSec 600
    } catch {
        $errorMessage = $_.Exception.Message
    }
    $latencyMs = [Math]::Round(((Get-Date) - $start).TotalMilliseconds, 0)

    $answer = ""
    $apiSuccess = $false
    if ($response -and $response.code -eq 200 -and $response.data) {
        $apiSuccess = [bool]$response.data.success
        if ($response.data.answer) {
            $answer = [string]$response.data.answer
        }
        if (-not $apiSuccess -and $response.data.errorMessage) {
            $errorMessage = [string]$response.data.errorMessage
        }
    }

    $recordsResponse = $null
    try {
        $recordsResponse = Invoke-RestMethod -Uri "$BaseUrl/api/tool-invocations/$SessionId" -Method Get -TimeoutSec 30
    } catch {
    }
    $records = if ($null -eq $recordsResponse) { @() } else { @($recordsResponse) }
    $score = Score-Report $answer $records

    return [pscustomobject][ordered]@{
        endpoint = $Endpoint
        sessionId = $SessionId
        apiSuccess = $apiSuccess
        pass = ($apiSuccess -and $score.pass)
        latencyMs = $latencyMs
        reportGenerated = $score.reportGenerated
        alertHitCount = $score.alertHitCount
        qualityHitCount = $score.qualityHitCount
        toolCoverageCount = $score.toolCoverageCount
        prometheusUsed = $score.prometheusUsed
        logsUsed = $score.logsUsed
        skillUsed = $score.skillUsed
        topicUsed = $score.topicUsed
        forbiddenHit = $score.forbiddenHit
        toolInvocationCount = $records.Count
        invokedTools = (@($records | ForEach-Object { $_.toolName }) -join "|")
        answerLength = $answer.Length
        error = $errorMessage
        preview = if ($answer.Length -gt 300) { $answer.Substring(0, 300) } else { $answer }
    }
}

$resultDir = New-ResultDir $OutDir
$results = New-Object System.Collections.Generic.List[object]

Write-Host "AIOps architecture comparison output: $resultDir"
Write-Host "BaseUrl: $BaseUrl"
Write-Host "Runs: $Runs"

for ($i = 1; $i -le $Runs; $i++) {
    Write-Host "Running comparison [$i/$Runs]..."
    $singleSessionId = "aiops-single-$i-$(Get-Date -Format yyyyMMddHHmmss)-$(Get-Random)"
    $multiSessionId = "aiops-multi-$i-$(Get-Date -Format yyyyMMddHHmmss)-$(Get-Random)"

    $single = Invoke-AiOpsJson "/api/ai_ops_single" $singleSessionId $BaseUrl
    $multi = Invoke-AiOpsJson "/api/ai_ops_multi" $multiSessionId $BaseUrl

    $results.Add([pscustomobject]([ordered]@{ run = $i; mode = "single"; data = $single })) | Out-Null
    $results.Add([pscustomobject]([ordered]@{ run = $i; mode = "multi"; data = $multi })) | Out-Null

    Write-Host "  single pass=$($single.pass) latencyMs=$($single.latencyMs) tools=$($single.toolInvocationCount) coverage=$($single.toolCoverageCount)"
    Write-Host "  multi  pass=$($multi.pass) latencyMs=$($multi.latencyMs) tools=$($multi.toolInvocationCount) coverage=$($multi.toolCoverageCount)"
}

$flatResults = @($results | ForEach-Object {
    [pscustomobject][ordered]@{
        run = $_.run
        mode = $_.mode
        endpoint = $_.data.endpoint
        sessionId = $_.data.sessionId
        apiSuccess = $_.data.apiSuccess
        pass = $_.data.pass
        latencyMs = $_.data.latencyMs
        reportGenerated = $_.data.reportGenerated
        alertHitCount = $_.data.alertHitCount
        qualityHitCount = $_.data.qualityHitCount
        toolCoverageCount = $_.data.toolCoverageCount
        prometheusUsed = $_.data.prometheusUsed
        logsUsed = $_.data.logsUsed
        skillUsed = $_.data.skillUsed
        topicUsed = $_.data.topicUsed
        forbiddenHit = $_.data.forbiddenHit
        toolInvocationCount = $_.data.toolInvocationCount
        invokedTools = $_.data.invokedTools
        answerLength = $_.data.answerLength
        error = $_.data.error
        preview = $_.data.preview
    }
})

function Build-ModeSummary {
    param([string]$Mode)
    $items = @($flatResults | Where-Object mode -eq $Mode)
    [pscustomobject][ordered]@{
        mode = $Mode
        runs = $items.Count
        passCount = @($items | Where-Object pass -eq $true).Count
        passRate = Percent (@($items | Where-Object pass -eq $true).Count) $items.Count
        reportGeneratedRate = Percent (@($items | Where-Object reportGenerated -eq $true).Count) $items.Count
        avgLatencyMs = Average @($items | ForEach-Object { [double]$_.latencyMs })
        avgToolInvocations = Average @($items | ForEach-Object { [double]$_.toolInvocationCount })
        avgToolCoverage = Average @($items | ForEach-Object { [double]$_.toolCoverageCount })
        avgAlertHitCount = Average @($items | ForEach-Object { [double]$_.alertHitCount })
        avgQualityHitCount = Average @($items | ForEach-Object { [double]$_.qualityHitCount })
    }
}

$singleSummary = Build-ModeSummary "single"
$multiSummary = Build-ModeSummary "multi"

$summary = [ordered]@{
    generatedAt = (Get-Date).ToString("o")
    baseUrl = $BaseUrl
    runs = $Runs
    totalRequests = $flatResults.Count
    single = $singleSummary
    multi = $multiSummary
    improvements = [ordered]@{
        passRateDelta = [Math]::Round([double]$multiSummary.passRate - [double]$singleSummary.passRate, 2)
        reportGeneratedRateDelta = [Math]::Round([double]$multiSummary.reportGeneratedRate - [double]$singleSummary.reportGeneratedRate, 2)
        avgToolCoverageDelta = [Math]::Round([double]$multiSummary.avgToolCoverage - [double]$singleSummary.avgToolCoverage, 2)
        avgQualityHitCountDelta = [Math]::Round([double]$multiSummary.avgQualityHitCount - [double]$singleSummary.avgQualityHitCount, 2)
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
Write-Host "AIOps architecture comparison complete"
Write-Host "  Single pass rate: $($singleSummary.passRate)%"
Write-Host "  Multi pass rate: $($multiSummary.passRate)%"
Write-Host "  Pass rate delta: $($summary.improvements.passRateDelta)%"
Write-Host "  Single avg tool coverage: $($singleSummary.avgToolCoverage)"
Write-Host "  Multi avg tool coverage: $($multiSummary.avgToolCoverage)"
Write-Host "  Tool coverage delta: $($summary.improvements.avgToolCoverageDelta)"
Write-Host ""
Write-Host "Wrote:"
Write-Host "  $runsJson"
Write-Host "  $summaryJson"
Write-Host "  $runsCsv"
