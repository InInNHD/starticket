$ErrorActionPreference = "Stop"
$projectRoot = Split-Path $PSScriptRoot -Parent
Set-Location $projectRoot
Remove-Item Env:STARTICKET_DB_URL -ErrorAction SilentlyContinue
Remove-Item Env:STARTICKET_DEMO_ENABLED -ErrorAction SilentlyContinue
Remove-Item Env:STARTICKET_INFRA_ENABLED -ErrorAction SilentlyContinue
Remove-Item Env:STARTICKET_ORDER_RATE_LIMIT_MAX -ErrorAction SilentlyContinue
docker compose up -d --force-recreate backend frontend | Out-Host
Write-Host "已恢复默认 demo 配置：http://localhost:8081"
