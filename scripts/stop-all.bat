@echo off
REM ============================================================================
REM Order Processing Platform — Stop All Services
REM Run: scripts\stop-all.bat
REM ============================================================================

setlocal

if "%PG_BIN%"=="" set "PG_BIN=C:\sen\soft\pgsql\bin"
if "%PG_DATA%"=="" set "PG_DATA=C:\sen\soft\pgdata"
if "%KAFKA_HOME%"=="" set "KAFKA_HOME=C:\tools\kafka_2.13-3.9.0"

echo ============================================
echo  Order Processing Platform — Stopping...
echo ============================================
echo.

REM --- 1. Stop Spring Boot services ---
echo [1/4] Stopping Order Service...
for /f "tokens=2" %%a in ('tasklist /fi "WINDOWTITLE eq OrderService" /fo list 2^>nul ^| findstr /i "PID:"') do (
    taskkill /pid %%a /f >nul 2>&1
)
REM Also kill by jar name in case window title doesn't match
wmic process where "commandline like '%%order-service-1.0.0.jar%%'" call terminate >nul 2>&1
echo        Order Service stopped.

echo [2/4] Stopping Notification Service...
for /f "tokens=2" %%a in ('tasklist /fi "WINDOWTITLE eq NotificationService" /fo list 2^>nul ^| findstr /i "PID:"') do (
    taskkill /pid %%a /f >nul 2>&1
)
wmic process where "commandline like '%%notification-service-1.0.0.jar%%'" call terminate >nul 2>&1
echo        Notification Service stopped.

REM --- 2. Stop Kafka ---
echo [3/4] Stopping Kafka ^& Zookeeper...
if exist "%KAFKA_HOME%\bin\windows\kafka-server-stop.bat" (
    call "%KAFKA_HOME%\bin\windows\kafka-server-stop.bat" >nul 2>&1
    timeout /t 3 /nobreak >nul
)
for /f "tokens=2" %%a in ('tasklist /fi "WINDOWTITLE eq Kafka" /fo list 2^>nul ^| findstr /i "PID:"') do (
    taskkill /pid %%a /f >nul 2>&1
)
if exist "%KAFKA_HOME%\bin\windows\zookeeper-server-stop.bat" (
    call "%KAFKA_HOME%\bin\windows\zookeeper-server-stop.bat" >nul 2>&1
    timeout /t 2 /nobreak >nul
)
for /f "tokens=2" %%a in ('tasklist /fi "WINDOWTITLE eq Zookeeper" /fo list 2^>nul ^| findstr /i "PID:"') do (
    taskkill /pid %%a /f >nul 2>&1
)
echo        Kafka ^& Zookeeper stopped.

REM --- 3. Stop PostgreSQL ---
echo [4/4] Stopping PostgreSQL...
"%PG_BIN%\pg_ctl.exe" -D "%PG_DATA%" stop -m fast >nul 2>&1
if not %errorlevel%==0 (
    taskkill /im postgres.exe /f >nul 2>&1
)
echo        PostgreSQL stopped.

echo.
echo ============================================
echo  All services stopped.
echo ============================================

endlocal
