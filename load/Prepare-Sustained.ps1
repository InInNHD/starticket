param(
    [string]$BaseUrl = "http://localhost:18080",
    [int]$UserCount = 600
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path $PSScriptRoot -Parent
$runtimeDir = Join-Path $PSScriptRoot ".runtime"
New-Item -ItemType Directory -Force $runtimeDir | Out-Null
Set-Location $projectRoot

Write-Host "[1/5] 重建隔离数据库 starticket_perf"
docker compose up -d mysql redis rabbitmq | Out-Host
for ($attempt = 1; $attempt -le 60; $attempt++) {
    docker compose exec -T mysql mysqladmin ping -h localhost -uroot -proot_dev --silent 2>$null | Out-Null
    if ($LASTEXITCODE -eq 0) { break }
    if ($attempt -eq 60) { throw "MySQL 健康检查超时" }
    Start-Sleep -Seconds 1
}
@"
DROP DATABASE IF EXISTS starticket_perf;
CREATE DATABASE starticket_perf CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
GRANT ALL PRIVILEGES ON starticket_perf.* TO 'starticket'@'%';
FLUSH PRIVILEGES;
"@ | docker compose exec -T mysql mysql -uroot -proot_dev
if ($LASTEXITCODE -ne 0) { throw "无法创建 starticket_perf" }

Write-Host "[2/5] 启动后端并执行 Flyway 与 demo 初始化"
$env:STARTICKET_DB_URL = "jdbc:mysql://mysql:3306/starticket_perf?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
$env:STARTICKET_DEMO_ENABLED = "true"
$env:STARTICKET_INFRA_ENABLED = "false"
$env:STARTICKET_ORDER_RATE_LIMIT_MAX = "100000"
docker compose up -d --force-recreate backend | Out-Host
for ($attempt = 1; $attempt -le 90; $attempt++) {
    try {
        $health = Invoke-RestMethod "$BaseUrl/actuator/health" -TimeoutSec 2
        if ($health.status -eq "UP") { break }
    } catch {}
    if ($attempt -eq 90) { throw "后端健康检查超时" }
    Start-Sleep -Seconds 1
}

Write-Host "[3/5] 写入 600 个用户、5000 个座位和 36 个隔离场次"
Get-Content (Join-Path $PSScriptRoot "prepare-sustained.sql") -Raw |
    docker compose exec -T mysql mysql -ustarticket -pstarticket_dev starticket_perf | Out-Host
if ($LASTEXITCODE -ne 0) { throw "压测数据初始化失败" }

Write-Host "[4/5] 并行登录并保存短期 JWT（目录已被 git 忽略）"
$indices = 1..$UserCount
$tokens = $indices | ForEach-Object -Parallel {
    $username = "load{0:D4}" -f $_
    $body = @{ login = $username; password = "Password123" } | ConvertTo-Json -Compress
    $response = Invoke-RestMethod -Method Post -Uri "$using:BaseUrl/api/auth/login" `
        -ContentType "application/json" -Body $body -TimeoutSec 20
    $response.accessToken
} -ThrottleLimit 30
if ($tokens.Count -ne $UserCount -or $tokens.Where({ [string]::IsNullOrWhiteSpace($_) }).Count -gt 0) {
    throw "JWT 数量不完整：期望 $UserCount，实际 $($tokens.Count)"
}
$tokens | Set-Content (Join-Path $runtimeDir "tokens.txt") -Encoding utf8NoBOM

Write-Host "[5/5] 导出座位和压测矩阵"
$seatSql = "SELECT seat.id FROM st_seat seat JOIN st_venue_area area ON area.id=seat.area_id WHERE area.code='LOAD' ORDER BY seat.id;"
$seatIds = docker compose exec -T mysql mysql -N -B -ustarticket -pstarticket_dev starticket_perf -e $seatSql
$seatIds | Set-Content (Join-Path $runtimeDir "seats.txt") -Encoding utf8NoBOM

$matrixSql = @"
SELECT CONCAT(
  CASE WHEN name LIKE 'MYSQL-%' THEN 'MYSQL' ELSE 'REDIS' END, ',',
  CASE WHEN name LIKE '%-HOTSPOT-%' THEN 'HOTSPOT' ELSE 'SPREAD' END, ',',
  CAST(SUBSTRING(name, LOCATE('-C', name) + 2, 3) AS UNSIGNED), ',',
  CAST(RIGHT(name, 1) AS UNSIGNED), ',', id)
FROM st_performance
WHERE name LIKE 'MYSQL-%' OR name LIKE 'REDIS-%'
ORDER BY CASE WHEN name LIKE 'MYSQL-%' THEN 1 ELSE 2 END,
         CASE WHEN name LIKE '%-HOTSPOT-%' THEN 1 ELSE 2 END,
         CAST(SUBSTRING(name, LOCATE('-C', name) + 2, 3) AS UNSIGNED),
         CAST(RIGHT(name, 1) AS UNSIGNED);
"@
$matrix = docker compose exec -T mysql mysql -N -B -ustarticket -pstarticket_dev starticket_perf -e $matrixSql
@("Scheme,Mode,Concurrency,Round,PerformanceId") + $matrix |
    Set-Content (Join-Path $runtimeDir "matrix.csv") -Encoding utf8NoBOM

Write-Host "准备完成：$($tokens.Count) 个令牌、$($seatIds.Count) 个座位、$($matrix.Count) 个场次。"
