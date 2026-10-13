pipeline {
    agent any

    parameters {
        choice(
            name: 'FAIL_SCENARIO',
            choices: ['none', 'assertion', 'exception', 'validation', 'http5xx'],
            description: 'Opt-in Maven/Surefire failure. Use none for a green build.'
        )
        string(
            name: 'LOG_INGEST_URL',
            defaultValue: '',
            description: 'Optional ingest URL. Leave empty to skip upload. Also reads env LOG_INGEST_URL.'
        )
        string(
            name: 'LOG_INGEST_CREDENTIAL_ID',
            defaultValue: '',
            description: 'Optional Jenkins Secret Text credential ID. Also reads env LOG_INGEST_CREDENTIAL_ID.'
        )
        string(
            name: 'LOG_FIELD_JOB_NAME',
            defaultValue: 'job_name',
            description: 'Multipart field name for the Jenkins job name.'
        )
        string(
            name: 'LOG_FIELD_BUILD_NUMBER',
            defaultValue: 'build_number',
            description: 'Multipart field name for the build number.'
        )
        string(
            name: 'LOG_FIELD_BUILD_STATUS',
            defaultValue: 'build_status',
            description: 'Multipart field name for SUCCESS/FAILURE/UNSTABLE/ABORTED.'
        )
        string(
            name: 'LOG_FIELD_BUILD_URL',
            defaultValue: 'build_url',
            description: 'Multipart field name for the Jenkins build URL.'
        )
        string(
            name: 'LOG_FIELD_REPO_URL',
            defaultValue: 'repo_url',
            description: 'Multipart field name for the SCM repository URL.'
        )
        string(
            name: 'LOG_FIELD_COMMIT_SHA',
            defaultValue: 'commit_sha',
            description: 'Multipart field name for the commit SHA.'
        )
        string(
            name: 'LOG_FIELD_FILE',
            defaultValue: 'file',
            description: 'Multipart field name for the console log file.'
        )
    }

    options {
        timestamps()
    }

    environment {
        FAIL_SCENARIO = "${params.FAIL_SCENARIO ?: 'none'}"
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Build and Test') {
            steps {
                script {
                    if (isUnix()) {
                        sh '''
                            set -e
                            if [ -n "$FAIL_SCENARIO" ] && [ "$FAIL_SCENARIO" != "none" ]; then
                              mvn -B clean test -Dfail.scenario="$FAIL_SCENARIO"
                            else
                              mvn -B clean test
                            fi
                        '''
                    } else {
                        bat '''
                            @echo off
                            if /I not "%FAIL_SCENARIO%"=="none" if not "%FAIL_SCENARIO%"=="" (
                              mvn -B clean test -Dfail.scenario=%FAIL_SCENARIO%
                              goto :eof
                            )
                            mvn -B clean test
                        '''
                    }
                }
            }
        }

        stage('Package') {
            steps {
                script {
                    if (isUnix()) {
                        sh 'mvn -B package -DskipTests'
                    } else {
                        bat 'mvn -B package -DskipTests'
                    }
                }
            }
        }
    }

    post {
        always {
            script {
                def originalResult = currentBuild.currentResult ?: 'SUCCESS'
                catchError(buildResult: originalResult, stageResult: 'SUCCESS') {
                    forwardConsoleLog()
                }
            }
        }
    }
}

def forwardConsoleLog() {
    def ingestUrl = firstNonBlank(params.LOG_INGEST_URL, env.LOG_INGEST_URL)
    def credId = firstNonBlank(params.LOG_INGEST_CREDENTIAL_ID, env.LOG_INGEST_CREDENTIAL_ID)
    def consoleLog = "jenkins-console-${env.BUILD_NUMBER}.log"

    captureConsoleLog(consoleLog)

    if (!ingestUrl) {
        echo '[log-forward] LOG_INGEST_URL is not set; console log was captured in the workspace and upload was skipped.'
        return
    }

    def uploadEnv = [
        "CONSOLE_LOG=${consoleLog}",
        "LOG_INGEST_URL=${ingestUrl}",
        "LOG_BUILD_STATUS=${currentBuild.currentResult ?: ''}",
        "LOG_REPO_URL=${env.GIT_URL ?: ''}",
        "LOG_COMMIT_SHA=${env.GIT_COMMIT ?: ''}",
        "LOG_FIELD_JOB_NAME=${firstNonBlank(params.LOG_FIELD_JOB_NAME, env.LOG_FIELD_JOB_NAME, 'job_name')}",
        "LOG_FIELD_BUILD_NUMBER=${firstNonBlank(params.LOG_FIELD_BUILD_NUMBER, env.LOG_FIELD_BUILD_NUMBER, 'build_number')}",
        "LOG_FIELD_BUILD_STATUS=${firstNonBlank(params.LOG_FIELD_BUILD_STATUS, env.LOG_FIELD_BUILD_STATUS, 'build_status')}",
        "LOG_FIELD_BUILD_URL=${firstNonBlank(params.LOG_FIELD_BUILD_URL, env.LOG_FIELD_BUILD_URL, 'build_url')}",
        "LOG_FIELD_REPO_URL=${firstNonBlank(params.LOG_FIELD_REPO_URL, env.LOG_FIELD_REPO_URL, 'repo_url')}",
        "LOG_FIELD_COMMIT_SHA=${firstNonBlank(params.LOG_FIELD_COMMIT_SHA, env.LOG_FIELD_COMMIT_SHA, 'commit_sha')}",
        "LOG_FIELD_FILE=${firstNonBlank(params.LOG_FIELD_FILE, env.LOG_FIELD_FILE, 'file')}"
    ]

    if (credId) {
        withCredentials([string(credentialsId: credId, variable: 'LOG_INGEST_TOKEN')]) {
            withEnv(uploadEnv) {
                postConsoleLog()
            }
        }
    } else {
        withEnv(uploadEnv + ['LOG_INGEST_TOKEN=']) {
            postConsoleLog()
        }
    }
}

def captureConsoleLog(String consoleLog) {
    withEnv(["CONSOLE_LOG=${consoleLog}"]) {
        if (isUnix()) {
            sh '''
                set +e
                if [ -n "$BUILD_URL" ]; then
                  curl -s -k --globoff "${BUILD_URL}consoleText" > "$CONSOLE_LOG" 2>/dev/null
                fi
                if [ ! -s "$CONSOLE_LOG" ]; then
                  echo "[INFO] Build ${JOB_NAME} #${BUILD_NUMBER} completed with status: ${currentBuild:-unknown}" > "$CONSOLE_LOG"
                fi
                exit 0
            '''
        } else {
            powershell '''
                $ErrorActionPreference = "Continue"
                $ProgressPreference = "SilentlyContinue"
                $ok = $false
                if ($env:BUILD_URL) {
                    curl.exe -s -k --globoff "$($env:BUILD_URL)consoleText" -o $env:CONSOLE_LOG
                    if ((Test-Path $env:CONSOLE_LOG) -and ((Get-Item $env:CONSOLE_LOG).Length -gt 0)) {
                        $ok = $true
                    }
                }
                if (-not $ok) {
                    Set-Content -Path $env:CONSOLE_LOG -Value "[INFO] Build $($env:JOB_NAME) #$($env:BUILD_NUMBER) consoleText was empty or unavailable."
                }
                exit 0
            '''
        }
    }
}

def postConsoleLog() {
    if (isUnix()) {
        sh '''
            set +x
            set +e
            HTTP_CODE="000"
            if [ -n "$LOG_INGEST_TOKEN" ]; then
              HTTP_CODE=$(curl -s -o ingest_response.body -w "%{http_code}" \
                -X POST "$LOG_INGEST_URL" \
                -H "Authorization: Bearer ${LOG_INGEST_TOKEN}" \
                -F "${LOG_FIELD_JOB_NAME}=${JOB_NAME}" \
                -F "${LOG_FIELD_BUILD_NUMBER}=${BUILD_NUMBER}" \
                -F "${LOG_FIELD_BUILD_STATUS}=${LOG_BUILD_STATUS}" \
                -F "${LOG_FIELD_BUILD_URL}=${BUILD_URL}" \
                -F "${LOG_FIELD_REPO_URL}=${LOG_REPO_URL}" \
                -F "${LOG_FIELD_COMMIT_SHA}=${LOG_COMMIT_SHA}" \
                -F "${LOG_FIELD_FILE}=@${CONSOLE_LOG}") || HTTP_CODE="000"
            else
              HTTP_CODE=$(curl -s -o ingest_response.body -w "%{http_code}" \
                -X POST "$LOG_INGEST_URL" \
                -F "${LOG_FIELD_JOB_NAME}=${JOB_NAME}" \
                -F "${LOG_FIELD_BUILD_NUMBER}=${BUILD_NUMBER}" \
                -F "${LOG_FIELD_BUILD_STATUS}=${LOG_BUILD_STATUS}" \
                -F "${LOG_FIELD_BUILD_URL}=${BUILD_URL}" \
                -F "${LOG_FIELD_REPO_URL}=${LOG_REPO_URL}" \
                -F "${LOG_FIELD_COMMIT_SHA}=${LOG_COMMIT_SHA}" \
                -F "${LOG_FIELD_FILE}=@${CONSOLE_LOG}") || HTTP_CODE="000"
            fi
            echo "[log-forward] POST finished with HTTP ${HTTP_CODE} (non-fatal)."
            rm -f ingest_response.body
            exit 0
        '''
    } else {
        powershell '''
            $ErrorActionPreference = "Continue"
            $ProgressPreference = "SilentlyContinue"
            $curlArgs = @(
                "-s", "-o", "ingest_response.body", "-w", "%{http_code}",
                "-X", "POST", $env:LOG_INGEST_URL,
                "-F", "$($env:LOG_FIELD_JOB_NAME)=$($env:JOB_NAME)",
                "-F", "$($env:LOG_FIELD_BUILD_NUMBER)=$($env:BUILD_NUMBER)",
                "-F", "$($env:LOG_FIELD_BUILD_STATUS)=$($env:LOG_BUILD_STATUS)",
                "-F", "$($env:LOG_FIELD_BUILD_URL)=$($env:BUILD_URL)",
                "-F", "$($env:LOG_FIELD_REPO_URL)=$($env:LOG_REPO_URL)",
                "-F", "$($env:LOG_FIELD_COMMIT_SHA)=$($env:LOG_COMMIT_SHA)",
                "-F", "$($env:LOG_FIELD_FILE)=@$($env:CONSOLE_LOG)"
            )
            if ($env:LOG_INGEST_TOKEN) {
                $curlArgs = @("-H", "Authorization: Bearer $($env:LOG_INGEST_TOKEN)") + $curlArgs
            }
            $httpCode = "000"
            try {
                $httpCode = & curl.exe @curlArgs
            } catch {
                $httpCode = "000"
            }
            Write-Host "[log-forward] POST finished with HTTP $httpCode (non-fatal)."
            Remove-Item -Force -ErrorAction SilentlyContinue ingest_response.body
            exit 0
        '''
    }
}

def firstNonBlank(Object... values) {
    for (value in values) {
        if (value != null) {
            def text = value.toString().trim()
            if (text) {
                return text
            }
        }
    }
    return ''
}
