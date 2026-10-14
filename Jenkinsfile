pipeline {
    agent any

    parameters {
        choice(
            name: 'FAIL_SCENARIO',
            choices: ['none', 'assertion', 'exception', 'validation', 'http5xx'],
            description: 'Opt-in Maven/Surefire failure. Use none for a green build.'
        )
        string(
            name: 'MAVEN_HOME',
            defaultValue: '',
            description: 'Optional Maven home or Maven bin directory. Also reads env MAVEN_HOME. Leave empty to use MAVEN_TOOL or Maven-3.9.11.'
        )
        string(
            name: 'MAVEN_TOOL',
            defaultValue: '',
            description: 'Optional Jenkins Global Tool Configuration Maven installation name. Leave empty to use Maven-3.9.11. Also reads env MAVEN_TOOL.'
        )
        string(
            name: 'LOG_INGEST_URL',
            defaultValue: '',
            description: 'Optional ingest URL. Leave empty to skip upload. Also reads env LOG_INGEST_URL.'
        )
        string(
            name: 'LOG_INGEST_CREDENTIAL_ID',
            defaultValue: '',
            description: 'Optional Jenkins Secret Text credential ID for the ingest backend. Also reads env LOG_INGEST_CREDENTIAL_ID.'
        )
        string(
            name: 'LOG_CONSOLE_CREDENTIAL_ID',
            defaultValue: '',
            description: 'Optional Jenkins Username/Password credential ID used to authenticate against the Jenkins consoleText endpoint. Leave empty for unauthenticated access. Also reads env LOG_CONSOLE_CREDENTIAL_ID.'
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

    environment {
        FAIL_SCENARIO = "${params.FAIL_SCENARIO ?: 'none'}"
        DEFAULT_MAVEN_TOOL = 'Maven-3.9.11'
    }

    options {
        // Run checkout in the explicit stage so its post handler still has the agent Launcher.
        skipDefaultCheckout(true)
    }

    tools {
        maven 'Maven-3.9.11'
    }

    stages {
        stage('Checkout') {
            steps {
                script {
                    def checkoutMetadata = checkout scm
                    env.LOG_REPO_URL = firstNonBlank(checkoutMetadata?.GIT_URL, checkoutMetadata?.GIT_URL_1, env.GIT_URL)
                    env.LOG_COMMIT_SHA = firstNonBlank(checkoutMetadata?.GIT_COMMIT, env.GIT_COMMIT)
                }
            }
            post {
                unsuccessful {
                    script {
                        forwardConsoleLogWithoutChangingBuildResult()
                    }
                }
            }
        }

        stage('Build and Test') {
            steps {
                script {
                    withMavenOnPath {
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
            post {
                unsuccessful {
                    script {
                        forwardConsoleLogWithoutChangingBuildResult()
                    }
                }
            }
        }

        stage('Package') {
            steps {
                script {
                    withMavenOnPath {
                        if (isUnix()) {
                            sh 'mvn -B package -DskipTests'
                        } else {
                            bat 'mvn -B package -DskipTests'
                        }
                    }
                }
            }
            post {
                always {
                    script {
                        forwardConsoleLogWithoutChangingBuildResult()
                    }
                }
            }
        }
    }
}

def forwardConsoleLogWithoutChangingBuildResult() {
    def originalResult = currentBuild.currentResult ?: 'SUCCESS'
    catchError(buildResult: originalResult, stageResult: 'SUCCESS') {
        forwardConsoleLog()
    }
}

def forwardConsoleLog() {
    def ingestUrl = firstNonBlank(params.LOG_INGEST_URL, env.LOG_INGEST_URL)
    def ingestCredId = firstNonBlank(params.LOG_INGEST_CREDENTIAL_ID, env.LOG_INGEST_CREDENTIAL_ID)
    def consoleCredId = firstNonBlank(params.LOG_CONSOLE_CREDENTIAL_ID, env.LOG_CONSOLE_CREDENTIAL_ID)
    def consoleLog = "jenkins-console-${env.BUILD_NUMBER}.log"

    // Capture console output; abort forwarding if capture failed or produced no real content.
    boolean captured = captureConsoleLog(consoleLog, consoleCredId)
    if (!captured) {
        error('[log-forward] Console log capture failed or returned empty content. Forwarding aborted.')
    }

    if (!ingestUrl) {
        echo '[log-forward] LOG_INGEST_URL is not set; console log was captured in the workspace and upload was skipped.'
        return
    }

    def uploadEnv = [
        "CONSOLE_LOG=${consoleLog}",
        "LOG_INGEST_URL=${ingestUrl}",
        "LOG_BUILD_STATUS=${currentBuild.currentResult ?: ''}",
        "LOG_REPO_URL=${firstNonBlank(env.LOG_REPO_URL, env.GIT_URL)}",
        "LOG_COMMIT_SHA=${firstNonBlank(env.LOG_COMMIT_SHA, env.GIT_COMMIT)}",
        "LOG_FIELD_JOB_NAME=${firstNonBlank(params.LOG_FIELD_JOB_NAME, env.LOG_FIELD_JOB_NAME, 'job_name')}",
        "LOG_FIELD_BUILD_NUMBER=${firstNonBlank(params.LOG_FIELD_BUILD_NUMBER, env.LOG_FIELD_BUILD_NUMBER, 'build_number')}",
        "LOG_FIELD_BUILD_STATUS=${firstNonBlank(params.LOG_FIELD_BUILD_STATUS, env.LOG_FIELD_BUILD_STATUS, 'build_status')}",
        "LOG_FIELD_BUILD_URL=${firstNonBlank(params.LOG_FIELD_BUILD_URL, env.LOG_FIELD_BUILD_URL, 'build_url')}",
        "LOG_FIELD_REPO_URL=${firstNonBlank(params.LOG_FIELD_REPO_URL, env.LOG_FIELD_REPO_URL, 'repo_url')}",
        "LOG_FIELD_COMMIT_SHA=${firstNonBlank(params.LOG_FIELD_COMMIT_SHA, env.LOG_FIELD_COMMIT_SHA, 'commit_sha')}",
        "LOG_FIELD_FILE=${firstNonBlank(params.LOG_FIELD_FILE, env.LOG_FIELD_FILE, 'file')}"
    ]

    if (ingestCredId) {
        withCredentials([string(credentialsId: ingestCredId, variable: 'LOG_INGEST_TOKEN')]) {
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

// Captures the Jenkins console text for this build to the given file.
// Returns true when a non-empty log file was written, false on failure.
// Accepts an optional Jenkins Username/Password credentialsId for authentication.
def captureConsoleLog(String consoleLog, String consoleCredId = '') {
    boolean success = false
    if (isUnix()) {
        def curlAuthArgs = ''
        if (consoleCredId) {
            withCredentials([usernamePassword(credentialsId: consoleCredId, usernameVariable: 'JENKINS_USER', passwordVariable: 'JENKINS_TOKEN')]) {
                curlAuthArgs = "-u \"\${JENKINS_USER}:\${JENKINS_TOKEN}\""
                success = _captureUnix(consoleLog, curlAuthArgs)
            }
        } else {
            success = _captureUnix(consoleLog, '')
        }
    } else {
        if (consoleCredId) {
            withCredentials([usernamePassword(credentialsId: consoleCredId, usernameVariable: 'JENKINS_USER', passwordVariable: 'JENKINS_TOKEN')]) {
                success = _captureWindows(consoleLog, true)
            }
        } else {
            success = _captureWindows(consoleLog, false)
        }
    }
    return success
}

def _captureUnix(String consoleLog, String curlAuthArgs) {
    withEnv(["CONSOLE_LOG=${consoleLog}"]) {
        def exitCode = sh(returnStatus: true, script: """
            set -e
            HTTP_CODE=\$(curl -sS -k --globoff -w "%{http_code}" -o "\${CONSOLE_LOG}" ${curlAuthArgs} "\${BUILD_URL}consoleText")
            if [ "\$HTTP_CODE" != "200" ]; then
              echo "[log-forward] consoleText fetch returned HTTP \$HTTP_CODE; discarding." >&2
              rm -f "\${CONSOLE_LOG}"
              exit 1
            fi
            if [ ! -s "\${CONSOLE_LOG}" ]; then
              echo "[log-forward] consoleText response was empty; discarding." >&2
              rm -f "\${CONSOLE_LOG}"
              exit 1
            fi
        """.stripIndent(), label: 'Capture Jenkins console log')
        return (exitCode == 0)
    }
}

def _captureWindows(String consoleLog, boolean useCredentials) {
    def scriptBody
    if (useCredentials) {
        scriptBody = '''
            $ErrorActionPreference = "Stop"
            $ProgressPreference = "SilentlyContinue"
            $curlArgs = @("-s", "-S", "-k", "--globoff", "-w", "%{http_code}", "-o", $env:CONSOLE_LOG,
                          "-u", "$($env:JENKINS_USER):$($env:JENKINS_TOKEN)",
                          "$($env:BUILD_URL)consoleText")
            $httpCode = & curl.exe @curlArgs
            if ($httpCode -ne "200") {
                Write-Error "[log-forward] consoleText fetch returned HTTP $httpCode; discarding."
                Remove-Item -Force -ErrorAction SilentlyContinue $env:CONSOLE_LOG
                exit 1
            }
            if (-not (Test-Path $env:CONSOLE_LOG) -or (Get-Item $env:CONSOLE_LOG).Length -eq 0) {
                Write-Error "[log-forward] consoleText response was empty; discarding."
                Remove-Item -Force -ErrorAction SilentlyContinue $env:CONSOLE_LOG
                exit 1
            }
            exit 0
        '''
    } else {
        scriptBody = '''
            $ErrorActionPreference = "Stop"
            $ProgressPreference = "SilentlyContinue"
            $curlArgs = @("-s", "-S", "-k", "--globoff", "-w", "%{http_code}", "-o", $env:CONSOLE_LOG,
                          "$($env:BUILD_URL)consoleText")
            $httpCode = & curl.exe @curlArgs
            if ($httpCode -ne "200") {
                Write-Error "[log-forward] consoleText fetch returned HTTP $httpCode; discarding."
                Remove-Item -Force -ErrorAction SilentlyContinue $env:CONSOLE_LOG
                exit 1
            }
            if (-not (Test-Path $env:CONSOLE_LOG) -or (Get-Item $env:CONSOLE_LOG).Length -eq 0) {
                Write-Error "[log-forward] consoleText response was empty; discarding."
                Remove-Item -Force -ErrorAction SilentlyContinue $env:CONSOLE_LOG
                exit 1
            }
            exit 0
        '''
    }
    withEnv(["CONSOLE_LOG=${consoleLog}"]) {
        def exitCode = powershell(returnStatus: true, script: scriptBody, label: 'Capture Jenkins console log')
        return (exitCode == 0)
    }
}

def postConsoleLog() {
    if (isUnix()) {
        sh '''
            set -e
            set +x
            if [ ! -s "$CONSOLE_LOG" ]; then
              echo "[log-forward] Console log file is missing or empty; aborting POST." >&2
              exit 1
            fi
            HTTP_CODE="000"
            if [ -n "$LOG_INGEST_TOKEN" ]; then
              HTTP_CODE=$(curl -sS -o ingest_response.body -w "%{http_code}" \
                -X POST "$LOG_INGEST_URL" \
                -H "Authorization: Bearer ${LOG_INGEST_TOKEN}" \
                -F "${LOG_FIELD_JOB_NAME}=${JOB_NAME}" \
                -F "${LOG_FIELD_BUILD_NUMBER}=${BUILD_NUMBER}" \
                -F "${LOG_FIELD_BUILD_STATUS}=${LOG_BUILD_STATUS}" \
                -F "${LOG_FIELD_BUILD_URL}=${BUILD_URL}" \
                -F "${LOG_FIELD_REPO_URL}=${LOG_REPO_URL}" \
                -F "${LOG_FIELD_COMMIT_SHA}=${LOG_COMMIT_SHA}" \
                -F "${LOG_FIELD_FILE}=@${CONSOLE_LOG}") || true
            else
              HTTP_CODE=$(curl -sS -o ingest_response.body -w "%{http_code}" \
                -X POST "$LOG_INGEST_URL" \
                -F "${LOG_FIELD_JOB_NAME}=${JOB_NAME}" \
                -F "${LOG_FIELD_BUILD_NUMBER}=${BUILD_NUMBER}" \
                -F "${LOG_FIELD_BUILD_STATUS}=${LOG_BUILD_STATUS}" \
                -F "${LOG_FIELD_BUILD_URL}=${BUILD_URL}" \
                -F "${LOG_FIELD_REPO_URL}=${LOG_REPO_URL}" \
                -F "${LOG_FIELD_COMMIT_SHA}=${LOG_COMMIT_SHA}" \
                -F "${LOG_FIELD_FILE}=@${CONSOLE_LOG}") || true
            fi
            rm -f ingest_response.body
            if echo "$HTTP_CODE" | grep -q "^2"; then
                echo "[log-forward] POST succeeded with HTTP ${HTTP_CODE}."
            else
                echo "[log-forward] POST failed with HTTP ${HTTP_CODE} (non-fatal)." >&2
                exit 1
            fi
        '''
    } else {
        powershell '''
            $ErrorActionPreference = "Continue"
            $ProgressPreference = "SilentlyContinue"

            if (-not (Test-Path $env:CONSOLE_LOG) -or (Get-Item $env:CONSOLE_LOG).Length -eq 0) {
                Write-Error "[log-forward] Console log file is missing or empty; aborting POST."
                exit 1
            }

            $curlArgs = @(
                "-s", "-S", "-o", "ingest_response.body", "-w", "%{http_code}",
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
                # Pass the full header value as a single array element; curl.exe receives it as one argument.
                $curlArgs += @("-H", "Authorization: Bearer $($env:LOG_INGEST_TOKEN)")
            }
            $httpCode = "000"
            try {
                $httpCode = & curl.exe @curlArgs
            } catch {
                $httpCode = "000"
            }
            Remove-Item -Force -ErrorAction SilentlyContinue ingest_response.body
            if ($httpCode -match "^2") {
                Write-Host "[log-forward] POST succeeded with HTTP $httpCode."
                exit 0
            } else {
                Write-Error "[log-forward] POST failed with HTTP $httpCode (non-fatal)."
                exit 1
            }
        '''
    }
}

def withMavenOnPath(Closure body) {
    def mavenHome = firstNonBlank(params.MAVEN_HOME)
    def mavenTool = firstNonBlank(params.MAVEN_TOOL, env.MAVEN_TOOL, env.DEFAULT_MAVEN_TOOL, 'Maven-3.9.11')
    if (!mavenHome) {
        echo "[maven] Resolving Jenkins Maven tool: ${mavenTool}"
        mavenHome = tool(mavenTool)
    }
    if (!mavenHome) {
        error("[maven] Could not resolve Maven. Configure Global Tool '${mavenTool}' or set MAVEN_HOME.")
    }
    def binDir = mavenBinDir(mavenHome)
    echo "[maven] Prepending Maven bin to PATH: ${binDir}"
    withEnv(["MAVEN_HOME=${mavenHomeDir(mavenHome)}", "PATH+MAVEN=${binDir}"]) {
        body()
    }
}

def mavenBinDir(String mavenHome) {
    def normalized = mavenHome.replace('\\', '/').replaceAll('/+$', '')
    if (normalized.toLowerCase().endsWith('/bin')) {
        return mavenHome
    }
    return isUnix() ? "${mavenHome}/bin" : "${mavenHome}\\bin"
}

def mavenHomeDir(String mavenHome) {
    def normalized = mavenHome.replace('\\', '/').replaceAll('/+$', '')
    if (normalized.toLowerCase().endsWith('/bin')) {
        def cut = mavenHome.length() >= 4 ? mavenHome.substring(0, mavenHome.length() - 4) : mavenHome
        return cut.replaceAll('[\\\\/]+$', '')
    }
    return mavenHome
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
