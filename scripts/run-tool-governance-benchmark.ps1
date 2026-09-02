param(
    [string]$BaseUrl = "http://localhost:9900",
    [int]$RunsPerCase = 3,
    [string]$CasesPath = "benchmark-data/tool-governance/cases.json",
    [string]$OutDir = "benchmark-results"
)

$ErrorActionPreference = "Stop"

function New-ResultDir {
    param([string]$BaseDir)
    $timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $path = Join-Path $BaseDir "tool-governance-$timestamp"
    New-Item -ItemType Directory -Force -Path $path | Out-Null
    return (Resolve-Path $path).Path
}

function Percent {
    param([int]$Numerator, [int]$Denominator)
    if ($Denominator -eq 0) { return 0 }
    return [Math]::Round(($Numerator / [double]$Denominator) * 100, 2)
}

function Contains-All {
    param([string]$Text, [object[]]$Keywords)
    foreach ($keyword in @($Keywords)) {
        if ([string]::IsNullOrWhiteSpace([string]$keyword)) { continue }
        if ($Text -notmatch [regex]::Escape([string]$keyword)) {
            return $false
        }
    }
    return $true
}

function Contains-Any {
    param([string]$Text, [object[]]$Keywords)
    foreach ($keyword in @($Keywords)) {
        if ([string]::IsNullOrWhiteSpace([string]$keyword)) { continue }
        if ($Text -match [regex]::Escape([string]$keyword)) {
            return $true
        }
    }
    return $false
}

function Get-OptionalBool {
    param(
        [object]$Object,
        [string]$Name,
        [bool]$Default
    )
    if ($null -eq $Object.PSObject.Properties[$Name]) {
        return $Default
    }
    return [bool]$Object.$Name
}

function Invoke-ToolGovernanceCase {
    param(
        [object]$Case,
        [int]$RunIndex,
        [string]$BaseUrl
    )

    $sessionId = "tool-governance-$($Case.id)-$RunIndex-$(Get-Date -Format yyyyMMddHHmmss)-$(Get-Random)"

    try {
        Invoke-RestMethod -Uri "$BaseUrl/api/tool-invocations/$sessionId" -Method Delete -TimeoutSec 30 | Out-Null
    } catch {
    }

    $payload = @{
        Id = $sessionId
        Question = [string]$Case.question
    } | ConvertTo-Json -Compress

    $start = Get-Date
    $response = $null
    $errorMessage = $null
    try {
        $response = Invoke-RestMethod `
            -Uri "$BaseUrl/api/chat" `
            -Method Post `
            -ContentType "application/json; charset=utf-8" `
            -Body $payload `
            -TimeoutSec 240
    } catch {
        $errorMessage = $_.Exception.Message
    }
    $latencyMs = [Math]::Round(((Get-Date) - $start).TotalMilliseconds, 0)

    $answer = ""
    if ($response -and $response.data -and $response.data.answer) {
        $answer = [string]$response.data.answer
    }

    $records = @()
    try {
        $recordsResponse = Invoke-RestMethod -Uri "$BaseUrl/api/tool-invocations/$sessionId" -Method Get -TimeoutSec 30
        if ($null -eq $recordsResponse) {
            $records = @()
        } else {
            $records = @($recordsResponse)
        }
    } catch {
        $errorMessage = if ($errorMessage) { $errorMessage } else { $_.Exception.Message }
    }

    $expectedTool = [string]$Case.expectedTool
    $expectNoTool = Get-OptionalBool $Case "expectNoTool" $false
    $expectedToolSuccess = Get-OptionalBool $Case "expectedToolSuccess" $true
    $expectStructuredError = Get-OptionalBool $Case "expectStructuredError" $false

    if ($expectNoTool) {
        $matchedRecords = @()
        $routePass = $records.Count -eq 0
    } else {
        $matchedRecords = @($records | Where-Object { $_.toolName -eq $expectedTool })
        $routePass = $matchedRecords.Count -gt 0
    }

    $resultText = ($matchedRecords | ForEach-Object { [string]$_.resultPreview }) -join "`n"
    $resultContainsPass = Contains-All $resultText @($Case.expectedResultContains)
    if ($expectNoTool) {
        $resultSuccessPass = $true
        $structuredErrorPass = $true
    } elseif ($expectedToolSuccess) {
        $resultSuccessPass = $matchedRecords.Count -gt 0 -and (@($matchedRecords | Where-Object { $_.success -eq $true }).Count -gt 0)
        $structuredErrorPass = $true
    } else {
        $resultSuccessPass = $matchedRecords.Count -gt 0 -and (@($matchedRecords | Where-Object { $_.success -eq $false }).Count -gt 0)
        $structuredErrorPass = (-not $expectStructuredError) -or ($resultText -match '"success"\s*:\s*false' -or $resultText -match '"status"\s*:\s*"(error|no_results)"')
    }
    $resultPass = $resultSuccessPass -and $resultContainsPass -and $structuredErrorPass

    $answerContainsPass = Contains-All $answer @($Case.expectedAnswerContains)
    $answerForbiddenHit = Contains-Any $answer @($Case.forbiddenAnswerContains)
    $missingAnswerKeywords = @($Case.expectedAnswerContains | Where-Object {
        -not [string]::IsNullOrWhiteSpace([string]$_) -and $answer -notmatch [regex]::Escape([string]$_)
    })
    $forbiddenAnswerHits = @($Case.forbiddenAnswerContains | Where-Object {
        -not [string]::IsNullOrWhiteSpace([string]$_) -and $answer -match [regex]::Escape([string]$_)
    })
    $faithfulPass = $answerContainsPass -and (-not $answerForbiddenHit)

    $pass = $response -and $response.code -eq 200 -and $response.data.success -eq $true -and $routePass -and $resultPass -and $faithfulPass
    $failureCategory = $null
    if (-not $pass) {
        if ($errorMessage) { $failureCategory = "REQUEST_ERROR" }
        elseif (-not $response -or $response.code -ne 200 -or $response.data.success -ne $true) { $failureCategory = "AGENT_ERROR" }
        elseif (-not $routePass) { $failureCategory = "ROUTE_MISS" }
        elseif (-not $resultPass) { $failureCategory = "TOOL_RESULT_MISMATCH" }
        elseif (-not $faithfulPass) { $failureCategory = "ANSWER_UNFAITHFUL" }
        else { $failureCategory = "ASSERTION_FAILED" }
    }

    return [pscustomobject][ordered]@{
        runId = [guid]::NewGuid().ToString()
        taskId = $Case.id
        taskName = $Case.name
        sessionId = $sessionId
        pass = $pass
        routePass = $routePass
        resultPass = $resultPass
        faithfulPass = $faithfulPass
        latencyMs = $latencyMs
        expectedTool = $expectedTool
        expectNoTool = $expectNoTool
        expectedToolSuccess = $expectedToolSuccess
        expectStructuredError = $expectStructuredError
        invokedTools = (@($records | ForEach-Object { $_.toolName }) -join "|")
        toolInvocationCount = $records.Count
        failureCategory = $failureCategory
        answerLength = $answer.Length
        missingAnswerKeywords = ($missingAnswerKeywords -join "|")
        forbiddenAnswerHits = ($forbiddenAnswerHits -join "|")
        error = $errorMessage
        answerPreview = if ($answer.Length -gt 300) { $answer.Substring(0, 300) } else { $answer }
        toolResultPreview = if ($resultText.Length -gt 300) { $resultText.Substring(0, 300) } else { $resultText }
    }
}

if (-not (Test-Path $CasesPath)) {
    throw "Cases file not found: $CasesPath"
}

$cases = Get-Content -Raw -Path $CasesPath | ConvertFrom-Json
$resultDir = New-ResultDir $OutDir
$results = New-Object System.Collections.Generic.List[object]

Write-Host "Tool governance benchmark output: $resultDir"
Write-Host "BaseUrl: $BaseUrl"
Write-Host "RunsPerCase: $RunsPerCase"

foreach ($case in $cases) {
    for ($i = 1; $i -le $RunsPerCase; $i++) {
        Write-Host "Running $($case.id) [$i/$RunsPerCase]..."
        $result = Invoke-ToolGovernanceCase $case $i $BaseUrl
        $results.Add($result) | Out-Null
        Write-Host "  pass=$($result.pass) route=$($result.routePass) result=$($result.resultPass) faithful=$($result.faithfulPass) failure=$($result.failureCategory)"
    }
}

$total = $results.Count
$passCount = @($results | Where-Object { $_.pass -eq $true }).Count
$routePassCount = @($results | Where-Object { $_.routePass -eq $true }).Count
$resultPassCount = @($results | Where-Object { $_.resultPass -eq $true }).Count
$faithfulPassCount = @($results | Where-Object { $_.faithfulPass -eq $true }).Count
$latencies = @($results | ForEach-Object { [double]$_.latencyMs })

$summary = [ordered]@{
    generatedAt = (Get-Date).ToString("o")
    baseUrl = $BaseUrl
    runsPerCase = $RunsPerCase
    totalRuns = $total
    passCount = $passCount
    failCount = $total - $passCount
    passRate = Percent $passCount $total
    routeAccuracy = Percent $routePassCount $total
    toolResultAccuracy = Percent $resultPassCount $total
    answerFaithfulness = Percent $faithfulPassCount $total
    avgLatencyMs = if ($latencies.Count -eq 0) { 0 } else { [Math]::Round(($latencies | Measure-Object -Average).Average, 0) }
    avgToolInvocations = if ($total -eq 0) { 0 } else { [Math]::Round((@($results | ForEach-Object { [double]$_.toolInvocationCount }) | Measure-Object -Average).Average, 2) }
    failureCategories = @($results | Where-Object { $_.pass -ne $true } | Group-Object failureCategory | ForEach-Object {
        [pscustomobject]@{
            category = $_.Name
            count = $_.Count
        }
    })
    tasks = @($results | Group-Object taskId | ForEach-Object {
        $items = @($_.Group)
        [pscustomobject]@{
            taskId = $_.Name
            runs = $items.Count
            passRate = Percent (@($items | Where-Object { $_.pass -eq $true }).Count) $items.Count
            routeAccuracy = Percent (@($items | Where-Object { $_.routePass -eq $true }).Count) $items.Count
            toolResultAccuracy = Percent (@($items | Where-Object { $_.resultPass -eq $true }).Count) $items.Count
            answerFaithfulness = Percent (@($items | Where-Object { $_.faithfulPass -eq $true }).Count) $items.Count
        }
    })
}

$runsJson = Join-Path $resultDir "runs.json"
$summaryJson = Join-Path $resultDir "summary.json"
$runsCsv = Join-Path $resultDir "runs.csv"

$results | ConvertTo-Json -Depth 8 | Set-Content -Path $runsJson -Encoding UTF8
$summary | ConvertTo-Json -Depth 8 | Set-Content -Path $summaryJson -Encoding UTF8
$results | Export-Csv -Path $runsCsv -NoTypeInformation -Encoding UTF8

Write-Host ""
Write-Host "Tool governance benchmark complete"
Write-Host "  Route accuracy: $($summary.routeAccuracy)%"
Write-Host "  Tool result accuracy: $($summary.toolResultAccuracy)%"
Write-Host "  Answer faithfulness: $($summary.answerFaithfulness)%"
Write-Host "  Pass rate: $($summary.passRate)%"
Write-Host ""
Write-Host "Wrote:"
Write-Host "  $runsJson"
Write-Host "  $summaryJson"
Write-Host "  $runsCsv"
