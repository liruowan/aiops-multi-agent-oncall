param(
    [string]$BaseUrl = "http://localhost:9900",
    [string]$FactsPath = "benchmark-data/memory-retrieval/facts.json",
    [string]$QueriesPath = "benchmark-data/memory-retrieval/queries.json",
    [string]$OutDir = "benchmark-results"
)

$ErrorActionPreference = "Stop"

function New-ResultDir {
    param([string]$BaseDir)
    $timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $path = Join-Path $BaseDir "memory-retrieval-$timestamp"
    New-Item -ItemType Directory -Force -Path $path | Out-Null
    return (Resolve-Path $path).Path
}

function ConvertTo-Percent {
    param([double]$Value)
    return [Math]::Round($Value * 100.0, 2)
}

$factsJson = Get-Content $FactsPath -Raw
$queriesJson = Get-Content $QueriesPath -Raw
$facts = $factsJson | ConvertFrom-Json
$queries = $queriesJson | ConvertFrom-Json
$sessionId = "memory-retrieval-benchmark-$(Get-Date -Format yyyyMMddHHmmss)-$(Get-Random)"

$payload = @"
{
  "sessionId": "$sessionId",
  "facts": $factsJson,
  "queries": $queriesJson
}
"@

$resultDir = New-ResultDir -BaseDir $OutDir
$start = Get-Date
$errorMessage = $null
$response = $null

try {
    $response = Invoke-RestMethod `
        -Uri "$BaseUrl/api/memory/retrieval/benchmark" `
        -Method Post `
        -ContentType "application/json; charset=utf-8" `
        -Body $payload `
        -TimeoutSec 120
} catch {
    $errorMessage = $_.Exception.Message
    if ($_.Exception.Response -ne $null) {
        try {
            $stream = $_.Exception.Response.GetResponseStream()
            $reader = New-Object System.IO.StreamReader($stream)
            $errorBody = $reader.ReadToEnd()
            if (-not [string]::IsNullOrWhiteSpace($errorBody)) {
                $errorMessage = "$errorMessage Body: $errorBody"
            }
        } catch {
            $errorMessage = "$errorMessage Body: <failed to read response body>"
        }
    }
}

$latencyMs = [Math]::Round(((Get-Date) - $start).TotalMilliseconds, 0)

if ($null -eq $response) {
    $summary = [pscustomobject]@{
        generatedAt = (Get-Date).ToString("s")
        baseUrl = $BaseUrl
        sessionId = $sessionId
        success = $false
        errorMessage = $errorMessage
        latencyMs = $latencyMs
    }
    $summary | ConvertTo-Json -Depth 8 | Set-Content -Path (Join-Path $resultDir "summary.json") -Encoding UTF8
    throw "Memory retrieval benchmark failed: $errorMessage"
}

$runs = New-Object System.Collections.Generic.List[object]
foreach ($result in $response.results) {
    $returnedIds = @($result.returnedFacts | ForEach-Object { $_.id })
    $runs.Add([pscustomobject]@{
        queryId = $result.queryId
        query = $result.query
        expectedFactIds = ($result.expectedFactIds -join ";")
        relevantFactIds = ($result.relevantFactIds -join ";")
        returnedFactIds = ($returnedIds -join ";")
        expectedCount = $result.expectedCount
        relevantCount = $result.relevantCount
        hitCount = $result.hitCount
        exactCoreHitCount = $result.exactCoreHitCount
        returnedCount = $result.returnedCount
        irrelevantCount = $result.irrelevantCount
        topKHitRate = $result.topKHitRate
        topKHitRatePercent = ConvertTo-Percent $result.topKHitRate
        exactCoreHitRate = $result.exactCoreHitRate
        exactCoreHitRatePercent = ConvertTo-Percent $result.exactCoreHitRate
        irrelevantInjectionRate = $result.irrelevantInjectionRate
        irrelevantInjectionRatePercent = ConvertTo-Percent $result.irrelevantInjectionRate
        baselineIrrelevantInjectionRate = $result.baselineIrrelevantInjectionRate
        baselineIrrelevantInjectionRatePercent = ConvertTo-Percent $result.baselineIrrelevantInjectionRate
    }) | Out-Null
}

$summary = [pscustomobject]@{
    generatedAt = (Get-Date).ToString("s")
    baseUrl = $BaseUrl
    sessionId = $response.sessionId
    success = $true
    latencyMs = $latencyMs
    totalFacts = $response.totalFacts
    totalQueries = $response.totalQueries
    totalHits = $response.totalHits
    totalExactCoreHits = $response.totalExactCoreHits
    totalExpectedCoreFacts = $response.totalExpectedCoreFacts
    totalReturnedFacts = $response.totalReturnedFacts
    totalIrrelevantReturnedFacts = $response.totalIrrelevantReturnedFacts
    topKHitRate = $response.topKHitRate
    topKHitRatePercent = ConvertTo-Percent $response.topKHitRate
    exactCoreHitRate = $response.exactCoreHitRate
    exactCoreHitRatePercent = ConvertTo-Percent $response.exactCoreHitRate
    irrelevantInjectionRate = $response.irrelevantInjectionRate
    irrelevantInjectionRatePercent = ConvertTo-Percent $response.irrelevantInjectionRate
    baselineIrrelevantInjectionRate = $response.baselineIrrelevantInjectionRate
    baselineIrrelevantInjectionRatePercent = ConvertTo-Percent $response.baselineIrrelevantInjectionRate
    irrelevantInjectionReductionRate = $response.irrelevantInjectionReductionRate
    irrelevantInjectionReductionRatePercent = ConvertTo-Percent $response.irrelevantInjectionReductionRate
    avgReturnedFacts = $response.avgReturnedFacts
}

$runsJson = Join-Path $resultDir "runs.json"
$summaryJson = Join-Path $resultDir "summary.json"
$runsCsv = Join-Path $resultDir "runs.csv"

$response.results | ConvertTo-Json -Depth 8 | Set-Content -Path $runsJson -Encoding UTF8
$summary | ConvertTo-Json -Depth 8 | Set-Content -Path $summaryJson -Encoding UTF8
$runs | Export-Csv -Path $runsCsv -NoTypeInformation -Encoding UTF8

Write-Host "Memory retrieval benchmark complete"
Write-Host ("  Core TopK hit rate: {0}%" -f $summary.topKHitRatePercent)
Write-Host ("  Exact core hit rate: {0}%" -f $summary.exactCoreHitRatePercent)
Write-Host ("  Irrelevant injection rate: {0}%" -f $summary.irrelevantInjectionRatePercent)
Write-Host ("  Baseline irrelevant injection rate: {0}%" -f $summary.baselineIrrelevantInjectionRatePercent)
Write-Host ("  Irrelevant injection reduction: {0}%" -f $summary.irrelevantInjectionReductionRatePercent)
Write-Host "Wrote:"
Write-Host "  $runsJson"
Write-Host "  $summaryJson"
Write-Host "  $runsCsv"
