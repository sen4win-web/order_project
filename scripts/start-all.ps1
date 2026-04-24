#!/usr/bin/env pwsh
# ============================================================================
# Order Processing Platform — Start All Services
# Run: powershell -ExecutionPolicy Bypass -File scripts/start-all.ps1
# ============================================================================

$ErrorActionPreference = "Stop"

# --- Configuration ---
$JAVA_HOME = $env:JAVA_HOME ?? "C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot"
$MAVEN_HOME = $env:MAVEN_HOME ?? "C:\tools\apache-maven-3.9.14"
$PG_BIN = $env:PG_BIN ?? "C:\sen\soft\pgsql\bin"
$PG_DATA = $env:PG_DATA ?? "C:\sen\soft\pgdata"
$KAFKA_HOME = $env:KAFKA_HOME ?? "C:\tools\kafka_2.13-3.9.0"
$PROJECT_ROOT = Split-Path -Parent $PSScriptRoot

$env:JAVA_HOME = $JAVA_HOME
$env:Path = "$env:Path;$JAVA_HOME\bin;$MAVEN_HOME\bin"

Write-Host "============================================" -ForegroundColor Cyan
Write-Host " Order Processing Platform — Starting...    " -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
Write-Host ""

# --- 1. PostgreSQL ---
Write-Host "[1/5] Starting PostgreSQL..." -ForegroundColor Yellow
$pgReady = & "$PG_BIN\pg_isready.exe" 2>$null
if ($LASTEXITCODE -eq 0) {
    Write-Host "       PostgreSQL already running." -ForegroundColor Green
} else {
    Start-Process -FilePath "$PG_BIN\pg_ctl.exe" -ArgumentList "start", "-D", $PG_DATA, "-l", "$PG_DATA\logfile" -NoNewWindow -Wait
    Start-Sleep -Seconds 3
    & "$PG_BIN\pg_isready.exe" | Out-Null
    if ($LASTEXITCODE -eq 0) {
        Write-Host "       PostgreSQL started on :5432" -ForegroundColor Green
    } else {
        Write-Host "       ERROR: PostgreSQL failed to start!" -ForegroundColor Red
        exit 1
    }
}

# --- 2. Zookeeper ---
Write-Host "[2/5] Starting Zookeeper..." -ForegroundColor Yellow
$zkProcess = Start-Process -FilePath "$KAFKA_HOME\bin\windows\zookeeper-server-start.bat" `
    -ArgumentList "$KAFKA_HOME\config\zookeeper.properties" `
    -PassThru -WindowStyle Minimized
Start-Sleep -Seconds 5
Write-Host "       Zookeeper started (PID: $($zkProcess.Id)) on :2181" -ForegroundColor Green

# --- 3. Kafka ---
Write-Host "[3/5] Starting Kafka Broker..." -ForegroundColor Yellow
$kafkaProcess = Start-Process -FilePath "$KAFKA_HOME\bin\windows\kafka-server-start.bat" `
    -ArgumentList "$KAFKA_HOME\config\server.properties" `
    -PassThru -WindowStyle Minimized
Start-Sleep -Seconds 8
Write-Host "       Kafka started (PID: $($kafkaProcess.Id)) on :9092" -ForegroundColor Green

# --- Create topic (idempotent) ---
Write-Host "       Creating order-events topic..." -ForegroundColor Gray
& "$KAFKA_HOME\bin\windows\kafka-topics.bat" --create --topic order-events --bootstrap-server localhost:9092 --partitions 3 --replication-factor 1 --if-not-exists 2>$null
Write-Host "       Topic ready." -ForegroundColor Green

# --- 4. Order Service ---
Write-Host "[4/5] Starting Order Service..." -ForegroundColor Yellow
$orderJar = Join-Path $PROJECT_ROOT "order-service\target\order-service-1.0.0.jar"
if (-not (Test-Path $orderJar)) {
    Write-Host "       JAR not found. Building..." -ForegroundColor Gray
    mvn clean package -DskipTests -f "$PROJECT_ROOT\order-service\pom.xml" -q
}
$orderProcess = Start-Process -FilePath "java" -ArgumentList "-jar", $orderJar `
    -PassThru -WindowStyle Minimized
Start-Sleep -Seconds 20
Write-Host "       Order Service started (PID: $($orderProcess.Id)) on :8081" -ForegroundColor Green

# --- 5. Notification Service ---
Write-Host "[5/5] Starting Notification Service..." -ForegroundColor Yellow
$notifJar = Join-Path $PROJECT_ROOT "notification-service\target\notification-service-1.0.0.jar"
if (-not (Test-Path $notifJar)) {
    Write-Host "       JAR not found. Building..." -ForegroundColor Gray
    mvn clean package -DskipTests -f "$PROJECT_ROOT\notification-service\pom.xml" -q
}
$notifProcess = Start-Process -FilePath "java" -ArgumentList "-jar", $notifJar `
    -PassThru -WindowStyle Minimized
Start-Sleep -Seconds 12
Write-Host "       Notification Service started (PID: $($notifProcess.Id)) on :8082" -ForegroundColor Green

# --- Health Check ---
Write-Host ""
Write-Host "============================================" -ForegroundColor Cyan
Write-Host " Health Check                               " -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
try {
    $orderHealth = (Invoke-RestMethod "http://localhost:8081/actuator/health" -TimeoutSec 5).status
    Write-Host " Order Service (8081):        $orderHealth" -ForegroundColor Green
} catch {
    Write-Host " Order Service (8081):        UNREACHABLE" -ForegroundColor Red
}
try {
    $notifHealth = (Invoke-RestMethod "http://localhost:8082/actuator/health" -TimeoutSec 5).status
    Write-Host " Notification Service (8082): $notifHealth" -ForegroundColor Green
} catch {
    Write-Host " Notification Service (8082): UNREACHABLE" -ForegroundColor Red
}

# --- Save PIDs ---
$pidFile = Join-Path $PROJECT_ROOT "scripts\.pids"
@"
ZK_PID=$($zkProcess.Id)
KAFKA_PID=$($kafkaProcess.Id)
ORDER_PID=$($orderProcess.Id)
NOTIF_PID=$($notifProcess.Id)
"@ | Set-Content $pidFile

Write-Host ""
Write-Host "============================================" -ForegroundColor Cyan
Write-Host " All services started!                      " -ForegroundColor Cyan
Write-Host " UI: http://localhost:3000                  " -ForegroundColor Cyan
Write-Host " API: http://localhost:8081                 " -ForegroundColor Cyan
Write-Host " To stop: scripts\stop-all.ps1              " -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
