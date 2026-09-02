param(
    [string]$BaseUrl = "http://localhost:9900",
    [int]$Sessions = 20,
    [int]$RoundsPerSession = 12,
    [string]$OutDir = "benchmark-results"
)

$ErrorActionPreference = "Stop"

function New-ResultDir {
    param([string]$BaseDir)
    $timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $path = Join-Path $BaseDir "memory-token-$timestamp"
    New-Item -ItemType Directory -Force -Path $path | Out-Null
    return (Resolve-Path $path).Path
}

function Invoke-Chat {
    param(
        [string]$BaseUrl,
        [string]$SessionId,
        [string]$Question
    )

    $payload = @{
        Id = $SessionId
        Question = $Question
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

    return [pscustomobject]@{
        response = $response
        latencyMs = $latencyMs
        errorMessage = $errorMessage
    }
}

function Get-MemoryStatus {
    param(
        [string]$BaseUrl,
        [string]$SessionId
    )

    $response = Invoke-RestMethod `
        -Uri "$BaseUrl/api/chat/session/$SessionId" `
        -Method Get `
        -TimeoutSec 30
    return $response.data
}

function Wait-CompressionIdle {
    param(
        [string]$BaseUrl,
        [string]$SessionId,
        [int]$TimeoutSec = 90
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    $status = Get-MemoryStatus -BaseUrl $BaseUrl -SessionId $SessionId
    while ($status.compressionStatus -eq "RUNNING" -and (Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 2
        $status = Get-MemoryStatus -BaseUrl $BaseUrl -SessionId $SessionId
    }
    return $status
}

function New-Question {
    param(
        [int]$SessionIndex,
        [int]$Round
    )

    $service = "order-service-$SessionIndex"
    $region = "ap-guangzhou"
    $namespace = "prod"
    $alert = "HighMemoryUsage"
    $pod = "order-worker-$Round"
    return "OnCall case round $Round. Service=$service, region=$region, namespace=$namespace, alert=$alert, pod=$pod, metric=jvm_memory_used_bytes, errorCode=OOM-RISK. Previous checks show heap usage keeps increasing, full GC cannot reclaim enough memory, and recent deployment changed cache behavior. Todo follow up: continue checking JVM heap, cache hit rate, pod restart history, log errors, and related metrics. Please analyze possible cause, mention useful facts, and keep context for follow up diagnosis."
}

$resultDir = New-ResultDir -BaseDir $OutDir
$runs = New-Object System.Collections.Generic.List[object]
$sessionSummaries = New-Object System.Collections.Generic.List[object]
$compressionPairs = New-Object System.Collections.Generic.List[object]

for ($sessionIndex = 1; $sessionIndex -le $Sessions; $sessionIndex++) {
    $sessionId = "memory-token-session-$sessionIndex-$(Get-Date -Format yyyyMMddHHmmss)-$(Get-Random)"
    Write-Host "Running session $sessionIndex/${Sessions}: $sessionId"
    $capturedPair = $null

    for ($round = 1; $round -le $RoundsPerSession; $round++) {
        $question = New-Question -SessionIndex $sessionIndex -Round $round
        $chatResult = Invoke-Chat -BaseUrl $BaseUrl -SessionId $sessionId -Question $question
        $status = Wait-CompressionIdle -BaseUrl $BaseUrl -SessionId $sessionId

        $passed = ($null -eq $chatResult.errorMessage) -and
            ($null -ne $chatResult.response) -and
            ($chatResult.response.code -eq 200) -and
            ($chatResult.response.data.success -eq $true)

        $runs.Add([pscustomobject]@{
            sessionId = $sessionId
            round = $round
            passed = $passed
            latencyMs = $chatResult.latencyMs
            errorMessage = $chatResult.errorMessage
            compressionStatus = $status.compressionStatus
            recentMessageCount = $status.recentMessageCount
            summaryCount = $status.summaryCount
            longTermFactCount = $status.longTermFactCount
            openTaskCount = $status.openTaskCount
            estimatedTokens = $status.estimatedTokens
            lastPromptTokens = $status.lastPromptTokens
            lastPromptTokensBeforeCompression = $status.lastPromptTokensBeforeCompression
            lastPromptTokensAfterCompression = $status.lastPromptTokensAfterCompression
            lastPromptTokenReductionRatio = $status.lastPromptTokenReductionRatio
            lastCompressionRatio = $status.lastCompressionRatio
            lastCompressionDurationMs = $status.lastCompressionDurationMs
        }) | Out-Null

        Write-Host ("  round={0}, pass={1}, prompt={2}, before={3}, after={4}, reduction={5:P2}, summaries={6}, facts={7}, tasks={8}" -f `
            $round,
            $passed,
            $status.lastPromptTokens,
            $status.lastPromptTokensBeforeCompression,
            $status.lastPromptTokensAfterCompression,
            $status.lastPromptTokenReductionRatio,
            $status.summaryCount,
            $status.longTermFactCount,
            $status.openTaskCount)

        if ($status.lastPromptTokensBeforeCompression -gt 0 -and
                $status.lastPromptTokensAfterCompression -gt 0) {
            $capturedPair = [pscustomobject]@{
                sessionId = $sessionId
                capturedRound = $round
                beforePromptTokens = $status.lastPromptTokensBeforeCompression
                afterPromptTokens = $status.lastPromptTokensAfterCompression
                promptTokenReductionRatio = $status.lastPromptTokenReductionRatio
                compressionRatio = $status.lastCompressionRatio
                compressionDurationMs = $status.lastCompressionDurationMs
                summaryCount = $status.summaryCount
                longTermFactCount = $status.longTermFactCount
                openTaskCount = $status.openTaskCount
                recentMessageCount = $status.recentMessageCount
            }
            $compressionPairs.Add($capturedPair) | Out-Null
            Write-Host ("  captured first compression pair: before={0}, after={1}, reduction={2:P2}, compressionMs={3}" -f `
                $capturedPair.beforePromptTokens,
                $capturedPair.afterPromptTokens,
                $capturedPair.promptTokenReductionRatio,
                $capturedPair.compressionDurationMs)
            break
        }
    }

    $finalStatus = Wait-CompressionIdle -BaseUrl $BaseUrl -SessionId $sessionId
    if ($null -eq $capturedPair -and
            $finalStatus.lastPromptTokensBeforeCompression -gt 0 -and
            $finalStatus.lastPromptTokensAfterCompression -gt 0) {
        $capturedPair = [pscustomobject]@{
            sessionId = $sessionId
            capturedRound = $RoundsPerSession
            beforePromptTokens = $finalStatus.lastPromptTokensBeforeCompression
            afterPromptTokens = $finalStatus.lastPromptTokensAfterCompression
            promptTokenReductionRatio = $finalStatus.lastPromptTokenReductionRatio
            compressionRatio = $finalStatus.lastCompressionRatio
            compressionDurationMs = $finalStatus.lastCompressionDurationMs
            summaryCount = $finalStatus.summaryCount
            longTermFactCount = $finalStatus.longTermFactCount
            openTaskCount = $finalStatus.openTaskCount
            recentMessageCount = $finalStatus.recentMessageCount
        }
        $compressionPairs.Add($capturedPair) | Out-Null
    }
    $sessionSummaries.Add([pscustomobject]@{
        sessionId = $sessionId
        rounds = $RoundsPerSession
        capturedCompressionPair = $null -ne $capturedPair
        summaryCount = $finalStatus.summaryCount
        longTermFactCount = $finalStatus.longTermFactCount
        openTaskCount = $finalStatus.openTaskCount
        recentMessageCount = $finalStatus.recentMessageCount
        lastPromptTokens = $finalStatus.lastPromptTokens
        promptTokensBeforeCompression = $finalStatus.lastPromptTokensBeforeCompression
        promptTokensAfterCompression = $finalStatus.lastPromptTokensAfterCompression
        promptTokenReductionRatio = $finalStatus.lastPromptTokenReductionRatio
        compressionRatio = $finalStatus.lastCompressionRatio
        compressionDurationMs = $finalStatus.lastCompressionDurationMs
    }) | Out-Null
}

$validReductions = @($compressionPairs | Where-Object { $_.beforePromptTokens -gt 0 -and $_.afterPromptTokens -gt 0 })
$avgReduction = 0
$avgBeforePromptTokens = 0
$avgAfterPromptTokens = 0
$avgCompressionDurationMs = 0
if ($validReductions.Count -gt 0) {
    $avgReduction = [Math]::Round((($validReductions | Measure-Object -Property promptTokenReductionRatio -Average).Average), 4)
    $avgBeforePromptTokens = [Math]::Round((($validReductions | Measure-Object -Property beforePromptTokens -Average).Average), 0)
    $avgAfterPromptTokens = [Math]::Round((($validReductions | Measure-Object -Property afterPromptTokens -Average).Average), 0)
    $avgCompressionDurationMs = [Math]::Round((($validReductions | Measure-Object -Property compressionDurationMs -Average).Average), 0)
}

$summary = [pscustomobject]@{
    generatedAt = (Get-Date).ToString("s")
    baseUrl = $BaseUrl
    sessions = $Sessions
    roundsPerSession = $RoundsPerSession
    totalRuns = $runs.Count
    passedRuns = @($runs | Where-Object { $_.passed }).Count
    failedRuns = @($runs | Where-Object { -not $_.passed }).Count
    compressedSessions = $validReductions.Count
    averagePromptTokensBeforeCompression = $avgBeforePromptTokens
    averagePromptTokensAfterCompression = $avgAfterPromptTokens
    averagePromptTokenReductionRatio = $avgReduction
    averageCompressionDurationMs = $avgCompressionDurationMs
    compressionPairs = $compressionPairs
    sessionSummaries = $sessionSummaries
}

$runsJson = Join-Path $resultDir "runs.json"
$summaryJson = Join-Path $resultDir "summary.json"
$runsCsv = Join-Path $resultDir "runs.csv"
$pairsCsv = Join-Path $resultDir "compression-pairs.csv"

$runs | ConvertTo-Json -Depth 8 | Set-Content -Path $runsJson -Encoding UTF8
$summary | ConvertTo-Json -Depth 8 | Set-Content -Path $summaryJson -Encoding UTF8
$runs | Export-Csv -Path $runsCsv -NoTypeInformation -Encoding UTF8
$compressionPairs | Export-Csv -Path $pairsCsv -NoTypeInformation -Encoding UTF8

Write-Host "Wrote:"
Write-Host "  $runsJson"
Write-Host "  $summaryJson"
Write-Host "  $runsCsv"
Write-Host "  $pairsCsv"
