param(
    [string]$BaseUrl = "http://localhost:9900",
    [int]$RunsPerCase = 5,
    [string]$OutDir = "benchmark-results"
)

$ErrorActionPreference = "Stop"

function New-ResultDir {
    param([string]$BaseDir)
    $timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $path = Join-Path $BaseDir "agent-architecture-$timestamp"
    New-Item -ItemType Directory -Force -Path $path | Out-Null
    return (Resolve-Path $path).Path
}

function Percentile {
    param(
        [double[]]$Values,
        [double]$Percent
    )
    if ($Values.Count -eq 0) {
        return 0
    }
    $sorted = $Values | Sort-Object
    $index = [Math]::Ceiling(($Percent / 100.0) * $sorted.Count) - 1
    $index = [Math]::Max(0, [Math]::Min($index, $sorted.Count - 1))
    return [Math]::Round($sorted[$index], 0)
}

function Classify-Failure {
    param(
        [string]$Endpoint,
        [object]$Response,
        [string]$Content,
        [string]$ErrorMessage
    )
    if ($ErrorMessage) {
        if ($ErrorMessage -match "timed out|timeout") { return "TIMEOUT" }
        if ($ErrorMessage -match "connection|refused|actively refused") { return "CONNECTION_ERROR" }
        return "REQUEST_ERROR"
    }
    if ($Endpoint -eq "/api/chat") {
        if ($null -eq $Response) { return "EMPTY_RESPONSE" }
        if ($Response.code -ne 200) { return "HTTP_OR_API_ERROR" }
        if ($Response.data.success -ne $true) { return "AGENT_ERROR" }
        return $null
    }
    if ([string]::IsNullOrWhiteSpace($Content)) { return "EMPTY_RESPONSE" }
    if ($Content -notmatch "event:message") { return "STREAM_FORMAT_ERROR" }
    return $null
}

function Invoke-ChatCase {
    param(
        [hashtable]$Case,
        [int]$RunIndex,
        [string]$BaseUrl
    )

    $sessionId = "$($Case.id)-run-$RunIndex-$(Get-Random)"
    $payload = @{
        Id = $sessionId
        Question = $Case.question
    } | ConvertTo-Json -Compress

    $start = Get-Date
    $errorMessage = $null
    $response = $null
    try {
        $response = Invoke-RestMethod `
            -Uri "$BaseUrl/api/chat" `
            -Method Post `
            -ContentType "application/json; charset=utf-8" `
            -Body $payload `
            -TimeoutSec 180
    } catch {
        $errorMessage = $_.Exception.Message
    }
    $latencyMs = [Math]::Round(((Get-Date) - $start).TotalMilliseconds, 0)

    $answer = ""
    if ($response -and $response.data -and $response.data.answer) {
        $answer = [string]$response.data.answer
    }

    $passed = $false
    if ($response -and $response.code -eq 200 -and $response.data.success -eq $true) {
        $passed = $true
        foreach ($keyword in $Case.requiredKeywords) {
            if ($answer -notmatch [regex]::Escape($keyword)) {
                $passed = $false
                break
            }
        }
    }

    $failureCategory = if ($passed) { $null } else { Classify-Failure "/api/chat" $response $answer $errorMessage }
    if (-not $passed -and -not $failureCategory) {
        $failureCategory = "ASSERTION_FAILED"
    }

    return [ordered]@{
        runId = [guid]::NewGuid().ToString()
        taskId = $Case.id
        taskName = $Case.name
        endpoint = "/api/chat"
        sessionId = $sessionId
        pass = $passed
        latencyMs = $latencyMs
        expectedToolSteps = $Case.expectedToolSteps
        observedToolEvidence = $Case.observedToolEvidence
        failureCategory = $failureCategory
        answerLength = $answer.Length
        error = $errorMessage
        preview = if ($answer.Length -gt 300) { $answer.Substring(0, 300) } else { $answer }
    }
}

function Invoke-StreamCase {
    param(
        [hashtable]$Case,
        [int]$RunIndex,
        [string]$BaseUrl
    )

    $sessionId = "$($Case.id)-run-$RunIndex-$(Get-Random)"
    $payload = @{
        Id = $sessionId
        Question = $Case.question
    } | ConvertTo-Json -Compress

    $start = Get-Date
    $errorMessage = $null
    $content = ""
    $statusCode = $null
    try {
        $response = Invoke-WebRequest `
            -Uri "$BaseUrl/api/chat_stream" `
            -Method Post `
            -ContentType "application/json; charset=utf-8" `
            -Body $payload `
            -TimeoutSec 180
        $statusCode = $response.StatusCode
        $content = [string]$response.Content
    } catch {
        $errorMessage = $_.Exception.Message
        if ($_.Exception.Response) {
            $statusCode = [int]$_.Exception.Response.StatusCode
        }
    }
    $latencyMs = [Math]::Round(((Get-Date) - $start).TotalMilliseconds, 0)

    $passed = $statusCode -eq 200 -and $content -match "event:message" -and $content -match '"type":"done"'
    foreach ($keyword in $Case.requiredKeywords) {
        if ($content -notmatch [regex]::Escape($keyword)) {
            $passed = $false
            break
        }
    }

    $failureCategory = if ($passed) { $null } else { Classify-Failure "/api/chat_stream" $null $content $errorMessage }
    if (-not $passed -and -not $failureCategory) {
        $failureCategory = "ASSERTION_FAILED"
    }

    return [ordered]@{
        runId = [guid]::NewGuid().ToString()
        taskId = $Case.id
        taskName = $Case.name
        endpoint = "/api/chat_stream"
        sessionId = $sessionId
        pass = $passed
        latencyMs = $latencyMs
        expectedToolSteps = $Case.expectedToolSteps
        observedToolEvidence = $Case.observedToolEvidence
        failureCategory = $failureCategory
        eventCount = ([regex]::Matches($content, "event:message")).Count
        answerLength = $content.Length
        error = $errorMessage
        preview = if ($content.Length -gt 300) { $content.Substring(0, 300) } else { $content }
    }
}

function Invoke-AiOpsCase {
    param(
        [hashtable]$Case,
        [int]$RunIndex,
        [string]$BaseUrl
    )

    $start = Get-Date
    $errorMessage = $null
    $content = ""
    $statusCode = $null
    try {
        $response = Invoke-WebRequest `
            -Uri "$BaseUrl/api/ai_ops" `
            -Method Post `
            -TimeoutSec 360
        $statusCode = $response.StatusCode
        $content = [string]$response.Content
    } catch {
        $errorMessage = $_.Exception.Message
        if ($_.Exception.Response) {
            $statusCode = [int]$_.Exception.Response.StatusCode
        }
    }
    $latencyMs = [Math]::Round(((Get-Date) - $start).TotalMilliseconds, 0)

    $passed = $statusCode -eq 200 -and $content -match "HighCPUUsage"
    foreach ($keyword in $Case.requiredKeywords) {
        if ($content -notmatch [regex]::Escape($keyword)) {
            $passed = $false
            break
        }
    }

    $failureCategory = if ($passed) { $null } else { Classify-Failure "/api/ai_ops" $null $content $errorMessage }
    if (-not $passed -and -not $failureCategory) {
        $failureCategory = "REPORT_FORMAT_ERROR"
    }

    return [ordered]@{
        runId = [guid]::NewGuid().ToString()
        taskId = $Case.id
        taskName = $Case.name
        endpoint = "/api/ai_ops"
        sessionId = ""
        pass = $passed
        latencyMs = $latencyMs
        expectedToolSteps = $Case.expectedToolSteps
        observedToolEvidence = $Case.observedToolEvidence
        failureCategory = $failureCategory
        eventCount = ([regex]::Matches($content, "event:message")).Count
        answerLength = $content.Length
        error = $errorMessage
        preview = if ($content.Length -gt 300) { $content.Substring(0, 300) } else { $content }
    }
}

$cases = @(
    @{
        id = "AGENT_BASIC"
        name = "Basic chat agent"
        type = "chat"
        question = "Briefly describe what you can do in one sentence."
        requiredKeywords = @("Prometheus")
        expectedToolSteps = 0
        observedToolEvidence = "answer_mentions_capabilities"
    },
    @{
        id = "TOOL_TIME"
        name = "Time tool ReAct call"
        type = "chat"
        question = "What time is it now? Please call the current time tool."
        requiredKeywords = @("time")
        expectedToolSteps = 1
        observedToolEvidence = "answer_contains_current_time"
    },
    @{
        id = "TOOL_PROMETHEUS"
        name = "Prometheus alert tool call"
        type = "chat"
        question = "Query current Prometheus active alerts and summarize them briefly."
        requiredKeywords = @("HighCPUUsage", "HighMemoryUsage", "SlowResponse")
        expectedToolSteps = 1
        observedToolEvidence = "answer_contains_mock_alerts"
    },
    @{
        id = "TOOL_LOGS"
        name = "QueryLogsTools log tool call"
        type = "chat"
        question = "Query recent memory-related logs for order-service. Use log topic system-metrics and region ap-guangzhou."
        requiredKeywords = @("order-service", "Full GC")
        expectedToolSteps = 1
        observedToolEvidence = "answer_contains_log_evidence"
    },
    @{
        id = "STREAM_CHAT"
        name = "SSE streaming output"
        type = "stream"
        question = "Explain CPU high usage alert troubleshooting steps in streaming mode. Keep the answer under 200 Chinese characters."
        requiredKeywords = @("CPU")
        expectedToolSteps = 0
        observedToolEvidence = "sse_event_messages"
    },
    @{
        id = "AIOPS_MULTI_AGENT"
        name = "AIOps multi-agent orchestration"
        type = "aiops"
        question = ""
        requiredKeywords = @("HighCPUUsage", "HighMemoryUsage", "SlowResponse")
        expectedToolSteps = 3
        observedToolEvidence = "final_report_contains_alert_sections"
    }
)

$resultDir = New-ResultDir $OutDir
$results = New-Object System.Collections.Generic.List[object]

Write-Host "Benchmark output: $resultDir"
Write-Host "BaseUrl: $BaseUrl"
Write-Host "RunsPerCase: $RunsPerCase"

foreach ($case in $cases) {
    for ($i = 1; $i -le $RunsPerCase; $i++) {
        Write-Host "Running $($case.id) [$i/$RunsPerCase]..."
        $result = switch ($case.type) {
            "chat" { Invoke-ChatCase $case $i $BaseUrl }
            "stream" { Invoke-StreamCase $case $i $BaseUrl }
            "aiops" { Invoke-AiOpsCase $case $i $BaseUrl }
            default { throw "Unknown case type: $($case.type)" }
        }
        $results.Add([pscustomobject]$result) | Out-Null
        Write-Host "  pass=$($result.pass) latencyMs=$($result.latencyMs) failure=$($result.failureCategory)"
    }
}

$latencies = @($results | ForEach-Object { [double]$_.latencyMs })
$passed = @($results | Where-Object { $_.pass -eq $true })
$failed = @($results | Where-Object { $_.pass -ne $true })
$summaryByTask = $results |
    Group-Object taskId |
    ForEach-Object {
        $items = @($_.Group)
        $itemLatencies = @($items | ForEach-Object { [double]$_.latencyMs })
        [pscustomobject]@{
            taskId = $_.Name
            runs = $items.Count
            passCount = @($items | Where-Object { $_.pass -eq $true }).Count
            passRate = [Math]::Round((@($items | Where-Object { $_.pass -eq $true }).Count / [double]$items.Count) * 100, 2)
            avgLatencyMs = [Math]::Round(($itemLatencies | Measure-Object -Average).Average, 0)
            p95LatencyMs = Percentile $itemLatencies 95
            avgExpectedToolSteps = [Math]::Round((@($items | ForEach-Object { [double]$_.expectedToolSteps }) | Measure-Object -Average).Average, 2)
        }
    }

$summary = [ordered]@{
    generatedAt = (Get-Date).ToString("o")
    baseUrl = $BaseUrl
    runsPerCase = $RunsPerCase
    totalRuns = $results.Count
    passCount = $passed.Count
    failCount = $failed.Count
    passRate = if ($results.Count -eq 0) { 0 } else { [Math]::Round(($passed.Count / [double]$results.Count) * 100, 2) }
    avgLatencyMs = if ($latencies.Count -eq 0) { 0 } else { [Math]::Round(($latencies | Measure-Object -Average).Average, 0) }
    p50LatencyMs = Percentile $latencies 50
    p95LatencyMs = Percentile $latencies 95
    p99LatencyMs = Percentile $latencies 99
    avgExpectedToolSteps = [Math]::Round((@($results | ForEach-Object { [double]$_.expectedToolSteps }) | Measure-Object -Average).Average, 2)
    failureCategories = @($failed | Group-Object failureCategory | ForEach-Object {
        [pscustomobject]@{
            category = $_.Name
            count = $_.Count
        }
    })
    tasks = $summaryByTask
}

$resultsPath = Join-Path $resultDir "runs.json"
$summaryPath = Join-Path $resultDir "summary.json"
$csvPath = Join-Path $resultDir "runs.csv"

$results | ConvertTo-Json -Depth 8 | Set-Content -Path $resultsPath -Encoding UTF8
$summary | ConvertTo-Json -Depth 8 | Set-Content -Path $summaryPath -Encoding UTF8
$results | Export-Csv -Path $csvPath -NoTypeInformation -Encoding UTF8

Write-Host ""
Write-Host "Summary:"
$summary | ConvertTo-Json -Depth 8
Write-Host ""
Write-Host "Wrote:"
Write-Host "  $resultsPath"
Write-Host "  $summaryPath"
Write-Host "  $csvPath"
