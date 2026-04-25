@echo off
REM ============================================================================
REM Order Processing Platform — Start All Services
REM Run: scripts\start-all.bat
REM ============================================================================

setlocal enabledelayedexpansion

REM --- Configuration (override with environment variables) ---
if "%JAVA_HOME%"=="" set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot"
if "%MAVEN_HOME%"=="" set "MAVEN_HOME=C:\tools\apache-maven-3.9.14"
if "%PG_BIN%"=="" set "PG_BIN=C:\sen\soft\pgsql\bin"
if "%PG_DATA%"=="" set "PG_DATA=C:\sen\soft\pgdata"
if "%KAFKA_HOME%"=="" set "KAFKA_HOME=C:\tools\kafka_2.13-3.9.0"

set "PATH=%JAVA_HOME%\bin;%MAVEN_HOME%\bin;%PATH%"
set "PROJECT_ROOT=%~dp0.."

echo ============================================
echo  Order Processing Platform — Starting...
echo ============================================
echo.

REM --- 1. PostgreSQL ---
echo [1/5] Starting PostgreSQL...
"%PG_BIN%\pg_isready.exe" >nul 2>&1
if %errorlevel%==0 (
    echo        PostgreSQL already running.
) else (
    "%PG_BIN%\pg_ctl.exe" start -D "%PG_DATA%" -l "%PG_DATA%\logfile"
    timeout /t 3 /nobreak >nul
    "%PG_BIN%\pg_isready.exe" >nul 2>&1
    if !errorlevel!==0 (
        echo        PostgreSQL started on :5432
    ) else (
        echo        ERROR: PostgreSQL failed to start!
        exit /b 1
    )
)

REM --- 2. Zookeeper ---
echo [2/5] Starting Zookeeper...
start "Zookeeper" /min "%KAFKA_HOME%\bin\windows\zookeeper-server-start.bat" "%KAFKA_HOME%\config\zookeeper.properties"
timeout /t 5 /nobreak >nul
echo        Zookeeper started on :2181

REM --- 3. Kafka ---
echo [3/5] Starting Kafka Broker...
start "Kafka" /min "%KAFKA_HOME%\bin\windows\kafka-server-start.bat" "%KAFKA_HOME%\config\server.properties"
timeout /t 8 /nobreak >nul
echo        Kafka started on :9092

REM --- Create topic (idempotent) ---
echo        Creating order-events topic...
call "%KAFKA_HOME%\bin\windows\kafka-topics.bat" --create --topic order-events --bootstrap-server localhost:9092 --partitions 3 --replication-factor 1 --if-not-exists >nul 2>&1
echo        Topic ready.

REM --- 4. Order Service ---
echo [4/5] Starting Order Service...
set "ORDER_JAR=%PROJECT_ROOT%\order-service\target\order-service-1.0.0.jar"
if not exist "%ORDER_JAR%" (
    echo        JAR not found. Building...
    call mvn clean package -DskipTests -f "%PROJECT_ROOT%\order-service\pom.xml" -q
)
start "OrderService" /min java -jar "%ORDER_JAR%"
echo        Waiting for Order Service to start...
timeout /t 25 /nobreak >nul
echo        Order Service started on :8081

REM --- 5. Notification Service ---
echo [5/5] Starting Notification Service...
set "NOTIF_JAR=%PROJECT_ROOT%\notification-service\target\notification-service-1.0.0.jar"
if not exist "%NOTIF_JAR%" (
    echo        JAR not found. Building...
    call mvn clean package -DskipTests -f "%PROJECT_ROOT%\notification-service\pom.xml" -q
)
start "NotificationService" /min java -jar "%NOTIF_JAR%"
echo        Waiting for Notification Service to start...
timeout /t 15 /nobreak >nul
echo        Notification Service started on :8082

REM --- Health Check ---
echo.
echo ============================================
echo  Health Check
echo ============================================
curl -s http://localhost:8081/actuator/health 2>nul | findstr /i "UP" >nul
if %errorlevel%==0 (
    echo  Order Service (8081):        UP
) else (
    echo  Order Service (8081):        UNREACHABLE
)
curl -s http://localhost:8082/actuator/health 2>nul | findstr /i "UP" >nul
if %errorlevel%==0 (
    echo  Notification Service (8082): UP
) else (
    echo  Notification Service (8082): UNREACHABLE
)

echo.
echo ============================================
echo  All services started!
echo  UI:  http://localhost:3000
echo  API: http://localhost:8081
echo  To stop: scripts\stop-all.bat
echo ============================================

endlocal
