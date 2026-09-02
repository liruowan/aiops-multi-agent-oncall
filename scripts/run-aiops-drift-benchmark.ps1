param(
    [string]$BaseUrl = "http://localhost:9900",
    [int]$Runs = 5,
    [string]$OutDir = "benchmark-results"
)

$ErrorActionPreference = "Stop"

function New-ResultDir {
    param([string]$BaseDir)
    $timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $path = Join-Path $BaseDir "aiops-drift-$timestamp"
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

function Score-Drift {
    param(
        [string]$Report,
        [object[]]$ToolRecords
    )

    $alertKeywords = @("HighCPUUsage", "HighMemoryUsage", "SlowResponse")
    $serviceKeywords = @("payment-service", "order-service", "user-service")
    $evidenceKeywords = @("CPU 92", "92%", "memory 91", "91%", "3.8GB", "Full GC", "OOMKilled", "OutOfMemoryError", "ConnectionPoolExhaustedException", "slow query", "P99", "4.2")
    $forbiddenDriftKeywords = @("DiskFull", "disk full", "Kafka broker", "Redis outage", "network partition", "database corrupted", "inventory-service", "checkout-service", "search-service")

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

    $alertHitCount = Count-Hits $Report $alertKeywords
    $serviceHitCount = Count-Hits $Report $serviceKeywords
    $evidenceHitCount = Count-Hits $Report $evidenceKeywords
    $forbiddenDriftHit = Has-Any $Report $forbiddenDriftKeywords
    $reportGenerated = -not [string]::IsNullOrWhiteSpace($Report)

    $conclusionPairCount = 0
    if (($Report -match "order-service") -and ($Report -match "memory|OOM|Full GC|3.8GB|91%")) { $conclusionPairCount++ }
    if (($Report -match "payment-service") -and ($Report -match "CPU|92%|HighCPUUsage")) { $conclusionPairCount++ }
    if (($Report -match "user-service") -and ($Report -match "latency|response|P99|SlowResponse|4.2")) { $conclusionPairCount++ }

    $categories = New-Object System.Collections.Generic.List[string]
    if (-not $reportGenerated) { $categories.Add("NO_REPORT") }
    if ($alertHitCount -lt 2) { $categories.Add("ALERT_DRIFT") }
    if ($serviceHitCount -lt 2) { $categories.Add("SERVICE_DRIFT") }
    if ($evidenceHitCount -lt 2) { $categories.Add("EVIDENCE_DRIFT") }
    if ((-not $prometheusUsed) -or (-not $logsUsed)) { $categories.Add("TOOL_DRIFT") }
    if ($conclusionPairCount -lt 1) { $categories.Add("CONCLUSION_DRIFT") }
    if ($forbiddenDriftHit) { $categories.Add("FORBIDDEN_DRIFT") }

    return [pscustomobject]@{
        driftPass = ($reportGenerated -and $categories.Count -eq 0)
        driftCategory = ($categories -join "|")
        reportGenerated = $reportGenerated
        alertHitCount = $alertHitCount
        serviceHitCount = $serviceHitCount
        evidenceHitCount = $evidenceHitCount
        conclusionPairCount = $conclusionPairCount
        toolCoverageCount = $toolCoverageCount
        prometheusUsed = $prometheusUsed
        logsUsed = $logsUsed
        skillUsed = $skillUsed
        topicUsed = $topicUsed
        forbiddenDriftHit = $forbiddenDriftHit
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

    $payload = @{ Id = $SessionId; Question = "AIOps architecture drift comparison" } | ConvertTo-Json -Compress
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
    $score = Score-Drift $answer $records

    return [pscustomobject][ordered]@{
        endpoint = $Endpoint
        sessionId = $SessionId
        apiSuccess = $apiSuccess
        pass = ($apiSuccess -and $score.driftPass)
        driftPass = $score.driftPass
        driftCategory = $score.driftCategory
        latencyMs = $latencyMs
        reportGenerated = $score.reportGenerated
        alertHitCount = $score.alertHitCount
        serviceHitCount = $score.serviceHitCount
        evidenceHitCount = $score.evidenceHitCount
        conclusionPairCount = $score.conclusionPairCount
        toolCoverageCount = $score.toolCoverageCount
        prometheusUsed = $score.prometheusUsed
        logsUsed = $score.logsUsed
        skillUsed = $score.skillUsed
        topicUsed = $score.topicUsed
        forbiddenDriftHit = $score.forbiddenDriftHit
        toolInvocationCount = $records.Count
        invokedTools = (@($records | ForEach-Object { $_.toolName }) -join "|")
        answerLength = $answer.Length
        error = $errorMessage
        preview = if ($answer.Length -gt 300) { $answer.Substring(0, 300) } else { $answer }
    }
}

function Build-ModeSummary {
    param(
        [string]$Mode,
        [object[]]$Items
    )
    $modeItems = @($Items | Where-Object mode -eq $Mode)
    $driftFailCount = @($modeItems | Where-Object driftPass -ne $true).Count
    [pscustomobject][ordered]@{
        mode = $Mode
        runs = $modeItems.Count
        passCount = @($modeItems | Where-Object pass -eq $true).Count
        passRate = Percent (@($modeItems | Where-Object pass -eq $true).Count) $modeItems.Count
        driftFailCount = $driftFailCount
        driftRate = Percent $driftFailCount $modeItems.Count
        reportGeneratedRate = Percent (@($modeItems | Where-Object reportGenerated -eq $true).Count) $modeItems.Count
        avgLatencyMs = Average @($modeItems | ForEach-Object { [double]$_.latencyMs })
        avgToolInvocations = Average @($modeItems | ForEach-Object { [double]$_.toolInvocationCount })
        avgToolCoverage = Average @($modeItems | ForEach-Object { [double]$_.toolCoverageCount })
        avgAlertHitCount = Average @($modeItems | ForEach-Object { [double]$_.alertHitCount })
        avgServiceHitCount = Average @($modeItems | ForEach-Object { [double]$_.serviceHitCount })
        avgEvidenceHitCount = Average @($modeItems | ForEach-Object { [double]$_.evidenceHitCount })
        avgConclusionPairCount = Average @($modeItems | ForEach-Object { [double]$_.conclusionPairCount })
        driftCategories = @($modeItems | Where-Object driftPass -ne $true | ForEach-Object { $_.driftCategory -split "\|" } | Where-Object { $_ } | Group-Object | ForEach-Object {
            [pscustomobject]@{ category = $_.Name; count = $_.Count }
        })
    }
}

$resultDir = New-ResultDir $OutDir
$flatResults = New-Object System.Collections.Generic.List[object]

Write-Host "AIOps architecture drift comparison output: $resultDir"
Write-Host "BaseUrl: $BaseUrl"
Write-Host "Runs: $Runs"

for ($i = 1; $i -le $Runs; $i++) {
    Write-Host "Running comparison [$i/$Runs]..."
    $singleSessionId = "aiops-single-$i-$(Get-Date -Format yyyyMMddHHmmss)-$(Get-Random)"
    $multiSessionId = "aiops-multi-$i-$(Get-Date -Format yyyyMMddHHmmss)-$(Get-Random)"

    $single = Invoke-AiOpsJson "/api/ai_ops_single" $singleSessionId $BaseUrl
    $multi = Invoke-AiOpsJson "/api/ai_ops_multi" $multiSessionId $BaseUrl

    foreach ($item in @(@{mode="single"; data=$single}, @{mode="multi"; data=$multi})) {
        $d = $item.data
        $flatResults.Add([pscustomobject][ordered]@{
            run = $i
            mode = $item.mode
            endpoint = $d.endpoint
            sessionId = $d.sessionId
            apiSuccess = $d.apiSuccess
            pass = $d.pass
            driftPass = $d.driftPass
            driftCategory = $d.driftCategory
            latencyMs = $d.latencyMs
            reportGenerated = $d.reportGenerated
            alertHitCount = $d.alertHitCount
            serviceHitCount = $d.serviceHitCount
            evidenceHitCount = $d.evidenceHitCount
            conclusionPairCount = $d.conclusionPairCount
            toolCoverageCount = $d.toolCoverageCount
            prometheusUsed = $d.prometheusUsed
            logsUsed = $d.logsUsed
            skillUsed = $d.skillUsed
            topicUsed = $d.topicUsed
            forbiddenDriftHit = $d.forbiddenDriftHit
            toolInvocationCount = $d.toolInvocationCount
            invokedTools = $d.invokedTools
            answerLength = $d.answerLength
            error = $d.error
            preview = $d.preview
        }) | Out-Null
    }

    Write-Host "  single driftPass=$($single.driftPass) drift=$($single.driftCategory) latencyMs=$($single.latencyMs) evidence=$($single.evidenceHitCount) tools=$($single.toolCoverageCount)"
    Write-Host "  multi  driftPass=$($multi.driftPass) drift=$($multi.driftCategory) latencyMs=$($multi.latencyMs) evidence=$($multi.evidenceHitCount) tools=$($multi.toolCoverageCount)"
}

$singleSummary = Build-ModeSummary "single" $flatResults
$multiSummary = Build-ModeSummary "multi" $flatResults
$driftReduction = if ([double]$singleSummary.driftRate -eq 0) { 0 } else { [Math]::Round((([double]$singleSummary.driftRate - [double]$multiSummary.driftRate) / [double]$singleSummary.driftRate) * 100.0, 2) }

$summary = [ordered]@{
    generatedAt = (Get-Date).ToString("o")
    baseUrl = $BaseUrl
    runs = $Runs
    totalRequests = $flatResults.Count
    scoring = [ordered]@{
        driftPassRule = "alertHitCount>=2 AND serviceHitCount>=2 AND evidenceHitCount>=2 AND prometheusUsed AND logsUsed AND conclusionPairCount>=1 AND no forbidden drift keywords"
        alertKeywords = @("HighCPUUsage", "HighMemoryUsage", "SlowResponse")
        serviceKeywords = @("payment-service", "order-service", "user-service")
        evidenceKeywords = @("CPU 92", "92%", "memory 91", "91%", "3.8GB", "Full GC", "OOMKilled", "OutOfMemoryError", "ConnectionPoolExhaustedException", "slow query", "P99", "4.2")
    }
    single = $singleSummary
    multi = $multiSummary
    improvements = [ordered]@{
        passRateDelta = [Math]::Round([double]$multiSummary.passRate - [double]$singleSummary.passRate, 2)
        driftRateDelta = [Math]::Round([double]$multiSummary.driftRate - [double]$singleSummary.driftRate, 2)
        driftRateReductionPercent = $driftReduction
        avgEvidenceHitCountDelta = [Math]::Round([double]$multiSummary.avgEvidenceHitCount - [double]$singleSummary.avgEvidenceHitCount, 2)
        avgToolCoverageDelta = [Math]::Round([double]$multiSummary.avgToolCoverage - [double]$singleSummary.avgToolCoverage, 2)
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
Write-Host "AIOps architecture drift comparison complete"
Write-Host "  Single drift rate: $($singleSummary.driftRate)%"
Write-Host "  Multi drift rate: $($multiSummary.driftRate)%"
Write-Host "  Drift rate delta: $($summary.improvements.driftRateDelta)%"
Write-Host "  Drift reduction: $($summary.improvements.driftRateReductionPercent)%"
Write-Host "  Single avg evidence hits: $($singleSummary.avgEvidenceHitCount)"
Write-Host "  Multi avg evidence hits: $($multiSummary.avgEvidenceHitCount)"
Write-Host ""
Write-Host "Wrote:"
Write-Host "  $runsJson"
Write-Host "  $summaryJson"
Write-Host "  $runsCsv"
