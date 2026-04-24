#!/usr/bin/env pwsh
# ============================================================================
# Order Processing Platform — Stop All Services
# Run: powershell -ExecutionPolicy Bypass -File scripts/stop-all.ps1
# ============================================================================

$ErrorActionPreference = "SilentlyContinue"

$PG_BIN = $env:PG_BIN ?? "C:\sen\soft\pgsql\bin"
$PG_DATA = $env:PG_DATA ?? "C:\sen\soft\pgdata"
$KAFKA_HOME = $env:KAFKA_HOME ?? "C:\tools\kafka_2.13-3.9.0"
$PROJECT_ROOT = Split-Path -Parent $PSScriptRoot

Write-Host "============================================" -ForegroundColor Cyan
Write-Host " Order Processing Platform — Stopping...    " -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
Write-Host ""

# --- Try to read saved PIDs ---
$pidFile = Join-Path $PROJECT_ROOT "scripts\.pids"
$savedPids = @{}
if (Test-Path $pidFile) {
    Get-Content $pidFile | ForEach-Object {
        if ($_ -match "^(\w+)=(\d+)$") {
            $savedPids[$Matches[1]] = [int]$Matches[2]
        }
    }
}

# --- 1. Stop Spring Boot services ---
Write-Host "[1/4] Stopping Order Service..." -ForegroundColor Yellow
if ($savedPids.ORDER_PID) {
    Stop-Process -Id $savedPids.ORDER_PID -Force -ErrorAction SilentlyContinue
}
Get-Process -Name "java" -ErrorAction SilentlyContinue | Where-Object {
    $_.CommandLine -like "*order-service*"
} | Stop-Process -Force -ErrorAction SilentlyContinue
Write-Host "       Order Service stopped." -ForegroundColor Green

Write-Host "[2/4] Stopping Notification Service..." -ForegroundColor Yellow
if ($savedPids.NOTIF_PID) {
    Stop-Process -Id $savedPids.NOTIF_PID -Force -ErrorAction SilentlyContinue
}
Get-Process -Name "java" -ErrorAction SilentlyContinue | Where-Object {
    $_.CommandLine -like "*notification-service*"
} | Stop-Process -Force -ErrorAction SilentlyContinue
Write-Host "       Notification Service stopped." -ForegroundColor Green

# --- 2. Stop Kafka ---
Write-Host "[3/4] Stopping Kafka & Zookeeper..." -ForegroundColor Yellow
if (Test-Path "$KAFKA_HOME\bin\windows\kafka-server-stop.bat") {
    & "$KAFKA_HOME\bin\windows\kafka-server-stop.bat" 2>$null
    Start-Sleep -Seconds 3
}
if ($savedPids.KAFKA_PID) {
    Stop-Process -Id $savedPids.KAFKA_PID -Force -ErrorAction SilentlyContinue
}
if (Test-Path "$KAFKA_HOME\bin\windows\zookeeper-server-stop.bat") {
    & "$KAFKA_HOME\bin\windows\zookeeper-server-stop.bat" 2>$null
    Start-Sleep -Seconds 2
}
if ($savedPids.ZK_PID) {
    Stop-Process -Id $savedPids.ZK_PID -Force -ErrorAction SilentlyContinue
}
Write-Host "       Kafka & Zookeeper stopped." -ForegroundColor Green

# --- 3. Stop PostgreSQL ---
Write-Host "[4/4] Stopping PostgreSQL..." -ForegroundColor Yellow
& "$PG_BIN\pg_ctl.exe" -D $PG_DATA stop -m fast 2>$null
if ($LASTEXITCODE -ne 0) {
    Stop-Process -Name "postgres" -Force -ErrorAction SilentlyContinue
}
Write-Host "       PostgreSQL stopped." -ForegroundColor Green

# --- Cleanup PID file ---
if (Test-Path $pidFile) { Remove-Item $pidFile -Force }

Write-Host ""
Write-Host "============================================" -ForegroundColor Cyan
Write-Host " All services stopped.                      " -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
