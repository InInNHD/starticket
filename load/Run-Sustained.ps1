param(
    [string]$BaseUrl = "http://localhost:18080",
    [int]$WarmupSeconds = 10,
    [int]$DurationSeconds = 30
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path $PSScriptRoot -Parent
$runtimeDir = Join-Path $PSScriptRoot ".runtime"
$resultsDir = Join-Path $PSScriptRoot "results"
$matrixPath = Join-Path $runtimeDir "matrix.csv"
$tokensPath = Join-Path $runtimeDir "tokens.txt"
$seatsPath = Join-Path $runtimeDir "seats.txt"
foreach ($required in @($matrixPath, $tokensPath, $seatsPath)) {
    if (-not (Test-Path $required)) { throw "缺少 $required，请先执行 load/Prepare-Sustained.ps1" }
}
New-Item -ItemType Directory -Force $resultsDir | Out-Null
Get-ChildItem $resultsDir -Filter "sustained-*.json" | Remove-Item -Force
Set-Location $projectRoot

function Wait-Backend {
    for ($attempt = 1; $attempt -le 90; $attempt++) {
        try {
            $health = Invoke-RestMethod "$BaseUrl/actuator/health" -TimeoutSec 2
            if ($health.status -eq "UP") { return }
        } catch {}
        Start-Sleep -Seconds 1
    }
    throw "后端健康检查超时"
}

function Set-Scheme([string]$scheme) {
    $env:STARTICKET_DB_URL = "jdbc:mysql://mysql:3306/starticket_perf?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
    $env:STARTICKET_DEMO_ENABLED = "false"
    $env:STARTICKET_INFRA_ENABLED = if ($scheme -eq "REDIS") { "true" } else { "false" }
    $env:STARTICKET_ORDER_RATE_LIMIT_MAX = "100000"
    docker compose up -d --force-recreate backend | Out-Host
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
    $line = docker compose exec -T redis redis-cli INFO stats |
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

$matrix = Import-Csv $matrixPath
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
        $name = "sustained-$($row.Scheme.ToLower())-$($row.Mode.ToLower())-c$($row.Concurrency)-r$($row.Round)"
        $outputPath = Join-Path $resultsDir "$name.json"
        $stdoutPath = Join-Path $runtimeDir "$name.out"
        $stderrPath = Join-Path $runtimeDir "$name.err"
        $redisBefore = Get-RedisCommands
        $cpu = [Collections.Generic.List[double]]::new()
        $heap = [Collections.Generic.List[double]]::new()
        $connections = [Collections.Generic.List[double]]::new()
        $arguments = @(
            "load/OrderRace.java", $BaseUrl, "@$tokensPath", $row.PerformanceId, "@$seatsPath",
            $row.Concurrency, $WarmupSeconds, $DurationSeconds, $row.Mode, $outputPath
        )
        Write-Host "[$completed/$($matrix.Count)] $($row.Scheme) $($row.Mode) C=$($row.Concurrency) R=$($row.Round)"
        $process = Start-Process -FilePath "java" -ArgumentList $arguments -WorkingDirectory $projectRoot `
            -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath
        while (-not $process.HasExited) {
            try {
                $metrics = (Invoke-WebRequest "$BaseUrl/actuator/prometheus" -TimeoutSec 3).Content
                $cpu.Add((Get-Number $metrics "process_cpu_usage") * 100)
                $heap.Add((Get-Heap $metrics) / 1MB)
                $connections.Add((Get-Number $metrics "hikaricp_connections_active"))
            } catch {}
            Start-Sleep -Seconds 1
            $process.Refresh()
        }
        if ($process.ExitCode -ne 0) {
            throw "压测进程失败：$(Get-Content $stderrPath -Raw)"
        }
        $redisAfter = Get-RedisCommands
        $result = Get-Content $outputPath -Raw | ConvertFrom-Json
        $result | Add-Member scheme $row.Scheme
        $result | Add-Member round ([int]$row.Round)
        $result | Add-Member avgProcessCpuPercent $(if ($cpu.Count) { ($cpu | Measure-Object -Average).Average } else { 0 })
        $result | Add-Member maxHeapMb $(if ($heap.Count) { ($heap | Measure-Object -Maximum).Maximum } else { 0 })
        $result | Add-Member maxDbConnections $(if ($connections.Count) { ($connections | Measure-Object -Maximum).Maximum } else { 0 })
        $result | Add-Member redisCommands $(if ($row.Scheme -eq "REDIS") {
                [math]::Max(0, $redisAfter - $redisBefore)
            } else {
                0
            })
        $result | ConvertTo-Json -Depth 5 | Set-Content $outputPath -Encoding utf8NoBOM
        Write-Host ("  {0:N2} req/s, P95 {1:N2} ms, P99 {2:N2} ms, tech errors {3:P2}" -f `
                $result.throughput, $result.p95Ms, $result.p99Ms, $result.technicalErrorRate)
    }

    $all = Get-ChildItem $resultsDir -Filter "sustained-*.json" | ForEach-Object {
        Get-Content $_.FullName -Raw | ConvertFrom-Json
    }
    $summary = foreach ($group in ($all | Group-Object scheme, mode, concurrency)) {
        $sample = $group.Group[0]
        [ordered]@{
            scheme = $sample.scheme
            mode = $sample.mode
            concurrency = [int]$sample.concurrency
            rounds = $group.Count
            medianThroughput = [math]::Round((Get-Median @($group.Group.throughput)), 2)
            medianAvgMs = [math]::Round((Get-Median @($group.Group.avgMs)), 2)
            medianP95Ms = [math]::Round((Get-Median @($group.Group.p95Ms)), 2)
            medianP99Ms = [math]::Round((Get-Median @($group.Group.p99Ms)), 2)
            medianTechnicalErrorRate = [math]::Round((Get-Median @($group.Group.technicalErrorRate)), 4)
            avgProcessCpuPercent = [math]::Round(($group.Group.avgProcessCpuPercent | Measure-Object -Average).Average, 2)
            maxHeapMb = [math]::Round(($group.Group.maxHeapMb | Measure-Object -Maximum).Maximum, 2)
            maxDbConnections = [math]::Round(($group.Group.maxDbConnections | Measure-Object -Maximum).Maximum, 0)
            medianRedisCommands = [math]::Round((Get-Median @($group.Group.redisCommands)), 0)
        }
    }
    $summary | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $resultsDir "sustained-summary.json") -Encoding utf8NoBOM

    Get-Content (Join-Path $PSScriptRoot "assert-no-oversell.sql") -Raw |
        docker compose exec -T mysql mysql -ustarticket -pstarticket_dev starticket_perf |
        Set-Content (Join-Path $resultsDir "no-oversell-assertion.txt") -Encoding utf8NoBOM
    Write-Host "全部压测完成，汇总写入 load/results/sustained-summary.json"
} finally {
    & (Join-Path $PSScriptRoot "Restore-Demo.ps1")
}
