// ============================================================================
// Order Processing Platform — Jenkins CI/CD Pipeline (Windows Local Deployment)
// ============================================================================
// Prerequisites:
//   - Jenkins installed on Windows (https://www.jenkins.io/download/)
//   - Java 17 configured in Jenkins (Manage Jenkins → Tools → JDK)
//   - Maven 3.9+ configured in Jenkins (Manage Jenkins → Tools → Maven)
//   - Git plugin installed
//   - Pipeline plugin installed
//
// Setup:
//   1. New Item → Pipeline
//   2. Pipeline → Definition: "Pipeline script from SCM"
//   3. SCM: Git → URL: https://github.com/sen4win-web/order_project.git
//   4. Branch: */main
//   5. Script Path: Jenkinsfile
// ============================================================================

pipeline {
    agent any

    tools {
        jdk 'JDK17'           // Configure in: Manage Jenkins → Tools → JDK installations
        maven 'Maven3'        // Configure in: Manage Jenkins → Tools → Maven installations
    }

    environment {
        JAVA_HOME = tool('JDK17')
        ORDER_JAR = 'order-service/target/order-service-1.0.0.jar'
        NOTIF_JAR = 'notification-service/target/notification-service-1.0.0.jar'
        DEPLOY_DIR = 'C:\\deploy\\order-platform'
    }

    options {
        timestamps()
        timeout(time: 15, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '10'))
    }

    stages {

        // ================================================================
        // STAGE 1: Checkout
        // ================================================================
        stage('Checkout') {
            steps {
                echo '=== Stage 1: Checkout ==='
                checkout scm
                bat 'git log --oneline -3'
            }
        }

        // ================================================================
        // STAGE 2: Build
        // ================================================================
        stage('Build') {
            parallel {
                stage('Build Order Service') {
                    steps {
                        echo '=== Building Order Service ==='
                        bat 'mvn clean compile -f order-service/pom.xml -B'
                    }
                }
                stage('Build Notification Service') {
                    steps {
                        echo '=== Building Notification Service ==='
                        bat 'mvn clean compile -f notification-service/pom.xml -B'
                    }
                }
            }
        }

        // ================================================================
        // STAGE 3: Static Analysis (SpotBugs)
        // ================================================================
        stage('Static Analysis') {
            parallel {
                stage('SpotBugs - Order Service') {
                    steps {
                        echo '=== SpotBugs: Order Service ==='
                        bat 'mvn compile spotbugs:check -f order-service/pom.xml -B'
                    }
                    post {
                        always {
                            archiveArtifacts artifacts: 'order-service/target/spotbugs*.xml', allowEmptyArchive: true
                        }
                    }
                }
                stage('SpotBugs - Notification Service') {
                    steps {
                        echo '=== SpotBugs: Notification Service ==='
                        bat 'mvn compile spotbugs:check -f notification-service/pom.xml -B'
                    }
                    post {
                        always {
                            archiveArtifacts artifacts: 'notification-service/target/spotbugs*.xml', allowEmptyArchive: true
                        }
                    }
                }
            }
        }

        // ================================================================
        // STAGE 4: Unit Tests
        // ================================================================
        stage('Test') {
            parallel {
                stage('Test Order Service') {
                    steps {
                        echo '=== Testing Order Service ==='
                        bat 'mvn test -f order-service/pom.xml -B'
                    }
                    post {
                        always {
                            junit allowEmptyResults: true, testResults: 'order-service/target/surefire-reports/*.xml'
                        }
                    }
                }
                stage('Test Notification Service') {
                    steps {
                        echo '=== Testing Notification Service ==='
                        bat 'mvn test -f notification-service/pom.xml -B'
                    }
                    post {
                        always {
                            junit allowEmptyResults: true, testResults: 'notification-service/target/surefire-reports/*.xml'
                        }
                    }
                }
            }
        }

        // ================================================================
        // STAGE 5: Package JARs
        // ================================================================
        stage('Package') {
            steps {
                echo '=== Packaging JARs ==='
                bat 'mvn package -DskipTests -f order-service/pom.xml -B'
                bat 'mvn package -DskipTests -f notification-service/pom.xml -B'
                archiveArtifacts artifacts: 'order-service/target/*.jar, notification-service/target/*.jar', fingerprint: true
            }
        }

        // ================================================================
        // STAGE 6: OWASP Dependency Check (Security)
        // ================================================================
        stage('Security Scan') {
            steps {
                echo '=== OWASP Dependency Check ==='
                bat 'mvn org.owasp:dependency-check-maven:check -f order-service/pom.xml -B -DfailBuildOnCVSS=9 || exit 0'
                bat 'mvn org.owasp:dependency-check-maven:check -f notification-service/pom.xml -B -DfailBuildOnCVSS=9 || exit 0'
            }
            post {
                always {
                    archiveArtifacts artifacts: '**/dependency-check-report.html', allowEmptyArchive: true
                }
            }
        }

        // ================================================================
        // STAGE 7: Deploy to Local Windows
        // ================================================================
        stage('Deploy') {
            when {
                branch 'main'
            }
            steps {
                echo '=== Deploying to Local Windows ==='

                // Stop existing services
                bat '''
                    echo Stopping existing services...
                    for /f "tokens=5" %%p in ('netstat -ano 2^>nul ^| findstr "LISTENING" ^| findstr /r ":8081[^0-9]"') do taskkill /pid %%p /t /f >nul 2>&1
                    for /f "tokens=5" %%p in ('netstat -ano 2^>nul ^| findstr "LISTENING" ^| findstr /r ":8082[^0-9]"') do taskkill /pid %%p /t /f >nul 2>&1
                    timeout /t 3 /nobreak >nul
                    echo Services stopped.
                '''

                // Copy JARs to deploy directory
                bat """
                    if not exist "${DEPLOY_DIR}" mkdir "${DEPLOY_DIR}"
                    copy /Y "order-service\\target\\order-service-1.0.0.jar" "${DEPLOY_DIR}\\"
                    copy /Y "notification-service\\target\\notification-service-1.0.0.jar" "${DEPLOY_DIR}\\"
                    echo JARs copied to ${DEPLOY_DIR}
                """

                // Start services
                bat """
                    echo Starting Order Service...
                    start "" /b javaw -jar "${DEPLOY_DIR}\\order-service-1.0.0.jar"
                    echo Starting Notification Service...
                    start "" /b javaw -jar "${DEPLOY_DIR}\\notification-service-1.0.0.jar"
                    echo Services starting...
                """

                // Wait and verify
                bat '''
                    echo Waiting 40s for services to start...
                    timeout /t 40 /nobreak >nul

                    echo === Health Check ===
                    curl -s http://localhost:8081/actuator/health | findstr /i "UP"
                    if %errorlevel%==0 (echo Order Service: UP) else (echo Order Service: FAILED & exit /b 1)

                    curl -s http://localhost:8082/actuator/health | findstr /i "UP"
                    if %errorlevel%==0 (echo Notification Service: UP) else (echo Notification Service: FAILED & exit /b 1)

                    echo === Deployment Successful ===
                '''
            }
        }

        // ================================================================
        // STAGE 8: Smoke Test (Post-Deploy)
        // ================================================================
        stage('Smoke Test') {
            when {
                branch 'main'
            }
            steps {
                echo '=== Running Smoke Tests ==='
                bat '''
                    echo [1] Login...
                    curl -s -X POST http://localhost:8081/auth/login -H "Content-Type: application/json" -d "{\\"username\\":\\"admin\\",\\"password\\":\\"admin123\\"}" > login_resp.txt
                    type login_resp.txt | findstr /i "token"
                    if %errorlevel% NEQ 0 (echo FAIL: Login & exit /b 1)
                    echo PASS: Login

                    echo [2] Create Order...
                    for /f "tokens=2 delims=:," %%a in ('type login_resp.txt ^| findstr /i "token"') do set RAW_TOKEN=%%a
                    set TOKEN=%RAW_TOKEN:"=%
                    set TOKEN=%TOKEN: =%

                    curl -s -o nul -w "%%{http_code}" -X POST http://localhost:8081/orders -H "Content-Type: application/json" -H "Authorization: Bearer %TOKEN%" -d "{\\"customerId\\":\\"jenkins\\",\\"productId\\":\\"smoke\\",\\"quantity\\":1}" > code.txt
                    set /p CODE=<code.txt
                    if "%CODE%"=="201" (echo PASS: Create Order) else (echo FAIL: Create Order = %CODE% & exit /b 1)

                    echo [3] Health Endpoints...
                    curl -s http://localhost:8081/actuator/health | findstr /i "UP" >nul
                    if %errorlevel%==0 (echo PASS: Order Health) else (echo FAIL: Order Health & exit /b 1)

                    curl -s http://localhost:8082/actuator/health | findstr /i "UP" >nul
                    if %errorlevel%==0 (echo PASS: Notification Health) else (echo FAIL: Notification Health & exit /b 1)

                    echo === ALL SMOKE TESTS PASSED ===
                    del login_resp.txt code.txt >nul 2>&1
                '''
            }
        }
    }

    // ================================================================
    // POST-PIPELINE
    // ================================================================
    post {
        success {
            echo '''
            ============================================================
            PIPELINE SUCCESS
            ============================================================
            Order Service:        http://localhost:8081
            Notification Service: http://localhost:8082
            Health:               http://localhost:8081/actuator/health
            ============================================================
            '''
        }
        failure {
            echo '''
            ============================================================
            PIPELINE FAILED — Check stage logs above
            ============================================================
            '''
        }
        always {
            cleanWs()
        }
    }
}
