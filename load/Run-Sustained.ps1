param(
    [string]$BaseUrl = "http://localhost:18081",
    [int]$Concurrency = 300,
    [int]$WarmupRequests = 300
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path $PSScriptRoot -Parent
$composeFile = Join-Path $PSScriptRoot "compose.perf.yml"
$runtimeDir = Join-Path $PSScriptRoot ".runtime"
$resultsDir = Join-Path $PSScriptRoot "results"
$matrixPath = Join-Path $runtimeDir "matrix.csv"
$tokensPath = Join-Path $runtimeDir "tokens.txt"
$warmupTokensPath = Join-Path $runtimeDir "warmup-tokens.txt"
$allSeatsPath = Join-Path $runtimeDir "seats.txt"
foreach ($required in @($matrixPath, $tokensPath, $warmupTokensPath, $allSeatsPath)) {
    if (-not (Test-Path $required)) { throw "缺少 $required，请先执行 load/Prepare-Sustained.ps1" }
}
New-Item -ItemType Directory -Force $resultsDir | Out-Null
Get-ChildItem $resultsDir -Filter "benchmark-*.json" | Remove-Item -Force
Set-Location $projectRoot

function Wait-Backend {
    for ($attempt = 1; $attempt -le 120; $attempt++) {
        try {
            $health = Invoke-RestMethod "$BaseUrl/actuator/health" -TimeoutSec 2
            if ($health.status -eq "UP") { return }
        } catch {}
        Start-Sleep -Seconds 1
    }
    throw "后端健康检查超时：$BaseUrl"
}

function Set-Scheme([string]$scheme) {
    $env:PERF_DEMO_ENABLED = "false"
    $env:PERF_INFRA_ENABLED = if ($scheme -eq "REDIS") { "true" } else { "false" }
    $env:PERF_REDIS_PRELOCK_ENABLED = if ($scheme -eq "REDIS") { "true" } else { "false" }
    docker compose -f $composeFile up -d --build --force-recreate backend | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "无法切换到 $scheme 方案" }
    Wait-Backend
}

function Get-Number([string]$text, [string]$name) {
    $match = [regex]::Match($text, "(?m)^$([regex]::Escape($name))(?:\{[^\r\n]*\})?\s+([-+0-9.eE]+)$")
    if ($match.Success) { return [double]::Parse($match.Groups[1].Value, [Globalization.CultureInfo]::InvariantCulture) }
    return 0.0
}

function Get-Heap([string]$text) {
    $total = 0.0
    foreach ($match in [regex]::Matches($text, '(?m)^jvm_memory_used_bytes\{[^\r\n]*area="heap"[^\r\n]*\}\s+([-+0-9.eE]+)$')) {
        $total += [double]::Parse($match.Groups[1].Value, [Globalization.CultureInfo]::InvariantCulture)
    }
    return $total
}

function Get-RedisCommands {
    $line = docker compose -f $composeFile exec -T redis redis-cli INFO stats |
        Where-Object { $_ -match '^total_commands_processed:' } | Select-Object -First 1
    if ($line -match ':(\d+)') { return [long]$Matches[1] }
    return 0L
}

function Get-Median([double[]]$values) {
    if ($values.Count -eq 0) { return 0.0 }
    $sorted = $values | Sort-Object
    $middle = [math]::Floor($sorted.Count / 2)
    if ($sorted.Count % 2) { return [double]$sorted[$middle] }
    return ([double]$sorted[$middle - 1] + [double]$sorted[$middle]) / 2
}

function Invoke-InventoryRun(
    [string]$runTokensPath,
    [long]$performanceId,
    [string]$seatsPath,
    [int]$requests,
    [string]$scenario,
    [string]$outputPath,
    [bool]$sampleMetrics
) {
    $resultName = [IO.Path]::GetFileNameWithoutExtension($outputPath)
    $stdoutPath = Join-Path $runtimeDir "$resultName.out"
    $stderrPath = Join-Path $runtimeDir "$resultName.err"
    $cpu = [Collections.Generic.List[double]]::new()
    $heap = [Collections.Generic.List[double]]::new()
    $connections = [Collections.Generic.List[double]]::new()
    $arguments = @(
        "load/OrderRace.java", "--inventory", $BaseUrl, "@$runTokensPath", $performanceId,
        "@$seatsPath", $Concurrency, $requests, $scenario, $outputPath
    )
    $process = Start-Process -FilePath "java" -ArgumentList $arguments -WorkingDirectory $projectRoot `
        -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath
    while (-not $process.HasExited) {
        if ($sampleMetrics) {
            try {
                $metrics = (Invoke-WebRequest "$BaseUrl/actuator/prometheus" -TimeoutSec 3).Content
                $cpu.Add((Get-Number $metrics "process_cpu_usage") * 100)
                $heap.Add((Get-Heap $metrics) / 1MB)
                $connections.Add((Get-Number $metrics "hikaricp_connections_active"))
            } catch {}
        }
        Start-Sleep -Milliseconds 250
        $process.Refresh()
    }
    if ($process.ExitCode -ne 0) {
        throw "压测进程失败：$(Get-Content $stderrPath -Raw)"
    }
    return [ordered]@{
        avgCpu = if ($cpu.Count) { ($cpu | Measure-Object -Average).Average } else { 0 }
        maxHeap = if ($heap.Count) { ($heap | Measure-Object -Maximum).Maximum } else { 0 }
        maxConnections = if ($connections.Count) { ($connections | Measure-Object -Maximum).Maximum } else { 0 }
    }
}

$matrix = Import-Csv $matrixPath
$allSeats = Get-Content $allSeatsPath | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
$currentScheme = ""
$completed = 0
try {
    foreach ($row in $matrix) {
        if ($row.Scheme -ne $currentScheme) {
            $currentScheme = $row.Scheme
            Write-Host "切换到 $currentScheme 方案"
            Set-Scheme $currentScheme
        }

        $completed++
        $scenario = $row.Scenario.ToUpper()
        $seatCount = [int]$row.SeatCount
        $requests = [int]$row.Requests
        $expectedSold = [int]$row.ExpectedSold
        $seatsPath = Join-Path $runtimeDir "seats-$seatCount.txt"
        $allSeats | Select-Object -First $seatCount | Set-Content $seatsPath -Encoding utf8NoBOM
        $name = "benchmark-$($row.Scheme.ToLower())-$($scenario.ToLower())-c$Concurrency-r$($row.Round)"
        $outputPath = Join-Path $resultsDir "$name.json"
        $warmupPath = Join-Path $runtimeDir "$name-warmup.json"

        Write-Host "[$completed/$($matrix.Count)] $($row.Scheme) $scenario：预热场次 $($row.WarmupPerformanceId)"
        Invoke-InventoryRun $warmupTokensPath ([long]$row.WarmupPerformanceId) $seatsPath $WarmupRequests $scenario $warmupPath $false | Out-Null

        Write-Host "  正式场次 $($row.PerformanceId)：$requests 个固定请求，$seatCount 个座位"
        $redisBefore = Get-RedisCommands
        $resources = Invoke-InventoryRun $tokensPath ([long]$row.PerformanceId) $seatsPath $requests $scenario $outputPath $true
        $redisAfter = Get-RedisCommands

        $salesSql = @"
SELECT COUNT(*), COUNT(DISTINCT i.seat_id), COUNT(*) - COUNT(DISTINCT i.seat_id)
FROM st_order_item i JOIN st_order o ON o.id=i.order_id
WHERE o.performance_id=$($row.PerformanceId) AND o.status IN ('PENDING_PAYMENT','PAID','REFUNDING');
"@
        $sales = (docker compose -f $composeFile exec -T mysql mysql -N -B -ustarticket -pstarticket_dev starticket_perf -e $salesSql).Trim() -split "`t"
        if ($LASTEXITCODE -ne 0 -or $sales.Count -ne 3) { throw "无法读取正式场次销售结果" }
        $soldCount = [int]$sales[0]
        $distinctSold = [int]$sales[1]
        $duplicateCount = [int]$sales[2]
        $oversellCount = [math]::Max(0, $soldCount - $seatCount)

        $result = Get-Content $outputPath -Raw | ConvertFrom-Json
        $result | Add-Member -NotePropertyName scheme -NotePropertyValue $row.Scheme
        $result | Add-Member -NotePropertyName scenario -NotePropertyValue $scenario
        $result | Add-Member -NotePropertyName round -NotePropertyValue ([int]$row.Round)
        $result | Add-Member -NotePropertyName warmupPerformanceId -NotePropertyValue ([long]$row.WarmupPerformanceId)
        $result | Add-Member -NotePropertyName performanceId -NotePropertyValue ([long]$row.PerformanceId)
        $result | Add-Member -NotePropertyName seatCount -NotePropertyValue $seatCount
        $result | Add-Member -NotePropertyName expectedSold -NotePropertyValue $expectedSold
        $result | Add-Member -NotePropertyName soldCount -NotePropertyValue $soldCount
        $result | Add-Member -NotePropertyName distinctSoldSeats -NotePropertyValue $distinctSold
        $result | Add-Member -NotePropertyName duplicateSeatCount -NotePropertyValue $duplicateCount
        $result | Add-Member -NotePropertyName oversellCount -NotePropertyValue $oversellCount
        $result | Add-Member -NotePropertyName inventoryAssertion -NotePropertyValue $(if ($soldCount -eq $expectedSold -and $distinctSold -eq $expectedSold -and $duplicateCount -eq 0 -and $oversellCount -eq 0) { "PASS" } else { "FAIL" })
        $result | Add-Member -NotePropertyName avgProcessCpuPercent -NotePropertyValue $resources.avgCpu
        $result | Add-Member -NotePropertyName maxHeapMb -NotePropertyValue $resources.maxHeap
        $result | Add-Member -NotePropertyName maxDbConnections -NotePropertyValue $resources.maxConnections
        $result | Add-Member -NotePropertyName redisCommands -NotePropertyValue $(if ($row.Scheme -eq "REDIS") { [math]::Max(0, $redisAfter - $redisBefore) } else { 0 })
        $result | ConvertTo-Json -Depth 5 | Set-Content $outputPath -Encoding utf8NoBOM

        $expectedConflicts = $requests - $expectedSold
        if ([int]$result.successCount -ne $expectedSold -or [int]$result.conflictCount -ne $expectedConflicts -or
            [int]$result.rateLimitedCount -ne 0 -or [int]$result.serverErrorCount -ne 0 -or
            [int]$result.transportErrorCount -ne 0 -or $result.inventoryAssertion -ne "PASS") {
            throw "结果断言失败：$name（201=$($result.successCount), 409=$($result.conflictCount), 429=$($result.rateLimitedCount), 5xx=$($result.serverErrorCount), inventory=$($result.inventoryAssertion)）"
        }
        Write-Host ("  201={0} 409={1} success={2:N2}/s conflict={3:N2}/s P95={4:N2}ms consistency={5}" -f `
            $result.successCount, $result.conflictCount, $result.successThroughput, $result.conflictThroughput, $result.p95Ms, $result.inventoryAssertion)
    }

    $all = Get-ChildItem $resultsDir -Filter "benchmark-*-c*-r*.json" | ForEach-Object {
        Get-Content $_.FullName -Raw | ConvertFrom-Json
    }
    $summary = foreach ($group in ($all | Group-Object scheme, scenario, concurrency)) {
        $sample = $group.Group[0]
        [ordered]@{
            scheme = $sample.scheme
            scenario = $sample.scenario
            concurrency = [int]$sample.concurrency
            seats = [int]$sample.seatCount
            requestsPerRound = [int]$sample.requests
            rounds = $group.Count
            medianThroughput = [math]::Round((Get-Median @($group.Group.throughput)), 2)
            medianSuccessThroughput = [math]::Round((Get-Median @($group.Group.successThroughput)), 2)
            medianConflictThroughput = [math]::Round((Get-Median @($group.Group.conflictThroughput)), 2)
            medianAvgMs = [math]::Round((Get-Median @($group.Group.avgMs)), 2)
            medianP95Ms = [math]::Round((Get-Median @($group.Group.p95Ms)), 2)
            medianP99Ms = [math]::Round((Get-Median @($group.Group.p99Ms)), 2)
            status201 = [int](Get-Median @($group.Group.successCount))
            status409 = [int](Get-Median @($group.Group.conflictCount))
            status429 = [int](Get-Median @($group.Group.rateLimitedCount))
            status5xx = [int](Get-Median @($group.Group.serverErrorCount))
            medianTechnicalErrorRate = [math]::Round((Get-Median @($group.Group.technicalErrorRate)), 4)
            sold = [int](Get-Median @($group.Group.soldCount))
            duplicateSeats = [int](Get-Median @($group.Group.duplicateSeatCount))
            oversold = [int](Get-Median @($group.Group.oversellCount))
            avgProcessCpuPercent = [math]::Round(($group.Group.avgProcessCpuPercent | Measure-Object -Average).Average, 2)
            maxHeapMb = [math]::Round(($group.Group.maxHeapMb | Measure-Object -Maximum).Maximum, 2)
            maxDbConnections = [math]::Round(($group.Group.maxDbConnections | Measure-Object -Maximum).Maximum, 0)
            medianRedisCommands = [math]::Round((Get-Median @($group.Group.redisCommands)), 0)
        }
    }
    $summary | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $resultsDir "benchmark-summary.json") -Encoding utf8NoBOM

    Get-Content (Join-Path $PSScriptRoot "assert-no-oversell.sql") -Raw |
        docker compose -f $composeFile exec -T mysql mysql -ustarticket -pstarticket_dev starticket_perf |
        Set-Content (Join-Path $resultsDir "benchmark-consistency-assertion.txt") -Encoding utf8NoBOM
    if ($LASTEXITCODE -ne 0) { throw "SQL 一致性断言执行失败" }
    if ((Get-Content (Join-Path $resultsDir "benchmark-consistency-assertion.txt") -Raw) -match "FAIL") {
        throw "SQL 一致性断言未通过"
    }
    Write-Host "全部正式测试完成：load/results/benchmark-summary.json"
} finally {
    Remove-Item Env:PERF_DEMO_ENABLED -ErrorAction SilentlyContinue
    Remove-Item Env:PERF_INFRA_ENABLED -ErrorAction SilentlyContinue
    Remove-Item Env:PERF_REDIS_PRELOCK_ENABLED -ErrorAction SilentlyContinue
    & (Join-Path $PSScriptRoot "Restore-Demo.ps1")
}
