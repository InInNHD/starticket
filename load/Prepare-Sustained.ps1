param(
    [string]$BaseUrl = "http://localhost:18081",
    [int]$UserCount = 1000
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path $PSScriptRoot -Parent
$composeFile = Join-Path $PSScriptRoot "compose.perf.yml"
$runtimeDir = Join-Path $PSScriptRoot ".runtime"
$resultsDir = Join-Path $PSScriptRoot "results"
New-Item -ItemType Directory -Force $runtimeDir, $resultsDir | Out-Null
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

try {
    Write-Host "[1/6] 启动隔离的 4 vCPU / 8 GB 压测环境"
    docker info | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Docker 未运行" }
    docker compose stop | Out-Host
    docker compose -f $composeFile down | Out-Host
    docker compose -f $composeFile up -d mysql redis rabbitmq | Out-Host
    for ($attempt = 1; $attempt -le 90; $attempt++) {
        docker compose -f $composeFile exec -T mysql mysqladmin ping -h localhost -uroot -proot_dev --silent 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) { break }
        if ($attempt -eq 90) { throw "MySQL 健康检查超时" }
        Start-Sleep -Seconds 1
    }

    Write-Host "[2/6] 重建 starticket_perf 并执行 Flyway/demo 初始化"
    @"
DROP DATABASE IF EXISTS starticket_perf;
CREATE DATABASE starticket_perf CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
GRANT ALL PRIVILEGES ON starticket_perf.* TO 'starticket'@'%';
FLUSH PRIVILEGES;
"@ | docker compose -f $composeFile exec -T mysql mysql -uroot -proot_dev
    if ($LASTEXITCODE -ne 0) { throw "无法创建 starticket_perf" }

    $env:PERF_DEMO_ENABLED = "true"
    $env:PERF_INFRA_ENABLED = "false"
    docker compose -f $composeFile up -d --build --force-recreate backend | Out-Host
    Wait-Backend

    Write-Host "[3/6] 写入独立预热/正式场次、$UserCount 个用户和 5000 个座位"
    Get-Content (Join-Path $PSScriptRoot "prepare-sustained.sql") -Raw |
        docker compose -f $composeFile exec -T mysql mysql -ustarticket -pstarticket_dev starticket_perf | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "压测数据初始化失败" }

    Write-Host "[4/6] 并行登录并保存正式测试专用 JWT"
    $tokens = 1..$UserCount | ForEach-Object -Parallel {
        $username = "load{0:D4}" -f $_
        $body = @{ login = $username; password = "Password123" } | ConvertTo-Json -Compress
        (Invoke-RestMethod -Method Post -Uri "$using:BaseUrl/api/auth/login" `
            -ContentType "application/json" -Body $body -TimeoutSec 20).accessToken
    } -ThrottleLimit 30
    if ($tokens.Count -ne $UserCount -or $tokens.Where({ [string]::IsNullOrWhiteSpace($_) }).Count -gt 0) {
        throw "JWT 数量不完整：期望 $UserCount，实际 $($tokens.Count)"
    }
    $tokens | Set-Content (Join-Path $runtimeDir "tokens.txt") -Encoding utf8NoBOM

    Write-Host "[5/6] 导出座位及 18 组正式压测矩阵"
    $seatSql = "SELECT seat.id FROM st_seat seat JOIN st_venue_area area ON area.id=seat.area_id WHERE area.code='LOAD' ORDER BY seat.id;"
    $seatIds = docker compose -f $composeFile exec -T mysql mysql -N -B -ustarticket -pstarticket_dev starticket_perf -e $seatSql
    $seatIds | Set-Content (Join-Path $runtimeDir "seats.txt") -Encoding utf8NoBOM

    $matrixSql = @"
SELECT formal.scheme, formal.scenario, formal.round_no,
       warmup.id AS warmup_performance_id, formal.id AS performance_id,
       CASE formal.scenario WHEN 'SINGLE' THEN 1 WHEN 'LIMITED' THEN 100 ELSE 1000 END AS seat_count,
       1000 AS requests,
       CASE formal.scenario WHEN 'SINGLE' THEN 1 WHEN 'LIMITED' THEN 100 ELSE 1000 END AS expected_sold
FROM (
    SELECT id,
           SUBSTRING_INDEX(name, '-', 1) AS scheme,
           SUBSTRING_INDEX(SUBSTRING_INDEX(name, '-', 2), '-', -1) AS scenario,
           CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(name, '-', 3), 'R', -1) AS UNSIGNED) AS round_no
    FROM st_performance WHERE name REGEXP '^(MYSQL|REDIS)-(SINGLE|LIMITED|SPREAD)-R[1-3]-FORMAL$'
) formal
JOIN st_performance warmup
  ON warmup.name = CONCAT(formal.scheme, '-', formal.scenario, '-R', formal.round_no, '-WARMUP')
ORDER BY FIELD(formal.scheme, 'MYSQL', 'REDIS'), FIELD(formal.scenario, 'SINGLE', 'LIMITED', 'SPREAD'), formal.round_no;
"@
    $matrix = docker compose -f $composeFile exec -T mysql mysql -N -B -ustarticket -pstarticket_dev starticket_perf -e $matrixSql
    @("Scheme,Scenario,Round,WarmupPerformanceId,PerformanceId,SeatCount,Requests,ExpectedSold") + ($matrix -replace "`t", ",") |
        Set-Content (Join-Path $runtimeDir "matrix.csv") -Encoding utf8NoBOM
    if ($matrix.Count -ne 18) { throw "压测矩阵数量错误：期望 18，实际 $($matrix.Count)" }

    Write-Host "[6/6] 记录可复现环境元数据"
    $limits = foreach ($service in @("backend", "mysql", "redis", "rabbitmq")) {
        $containerId = (docker compose -f $composeFile ps -q $service).Trim()
        if (-not $containerId) { throw "无法找到 $service 容器" }
        $inspect = docker inspect $containerId | ConvertFrom-Json
        [ordered]@{
            service = $service
            cpu = [math]::Round($inspect[0].HostConfig.NanoCpus / 1000000000, 2)
            memoryMb = [math]::Round($inspect[0].HostConfig.Memory / 1MB, 0)
        }
    }
    $gitCommit = (git rev-parse HEAD).Trim()
    [ordered]@{
        recordedAt = (Get-Date).ToString("o")
        gitCommit = $gitCommit
        serverBudget = "4 vCPU / 8 GB; load generator runs outside the server budget"
        containerCaps = "4 vCPU / 7168 MB; 1 GB reserved for Docker overhead"
        services = $limits
        jvm = "-Xms512m -Xmx1g -XX:+UseG1GC"
        hikariMaximumPoolSize = 10
        loadUsers = $UserCount
        venueSeats = $seatIds.Count
        formalRuns = 18
        formalRequestsPerRun = 1000
        command = ".\\load\\Prepare-Sustained.ps1; .\\load\\Run-Sustained.ps1"
    } | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $resultsDir "performance-environment.json") -Encoding utf8NoBOM

    Write-Host "准备完成：$($tokens.Count) 个令牌、$($seatIds.Count) 个座位、$($matrix.Count) 组正式测试。"
} catch {
    & (Join-Path $PSScriptRoot "Restore-Demo.ps1")
    throw
} finally {
    Remove-Item Env:PERF_DEMO_ENABLED -ErrorAction SilentlyContinue
    Remove-Item Env:PERF_INFRA_ENABLED -ErrorAction SilentlyContinue
}
