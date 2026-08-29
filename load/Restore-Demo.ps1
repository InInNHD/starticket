$ErrorActionPreference = "Stop"
$projectRoot = Split-Path $PSScriptRoot -Parent
$composeFile = Join-Path $PSScriptRoot "compose.perf.yml"
Set-Location $projectRoot
Remove-Item Env:PERF_DEMO_ENABLED -ErrorAction SilentlyContinue
Remove-Item Env:PERF_INFRA_ENABLED -ErrorAction SilentlyContinue
# 压测库和 RabbitMQ 数据均由脚本生成，结束后删除，避免下一轮继承库存或凭据。
docker compose -f $composeFile down -v | Out-Host
docker compose up -d | Out-Host
Write-Host "已停止隔离压测环境并恢复默认 demo：http://localhost:8081"
