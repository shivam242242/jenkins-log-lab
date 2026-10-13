# jenkins-log-lab

Standalone Spring Boot 3 / Java 17 demo that produces real Maven, Surefire, and Jenkins console logs. It does not depend on any particular analysis backend.

Jenkins checks this project out into the **job workspace**, builds it with Maven, and can optionally POST the console log to whatever ingest URL you configure.

## Setup

- JDK 17+
- Apache Maven 3.9+
- Optional: Jenkins with a **Windows** agent (JDK and Maven on the PATH)

From the project directory (or the Jenkins workspace after checkout):

```bat
mvn test
mvn package
```

Run the sample API locally:

```bat
mvn spring-boot:run
```

Health: `GET http://localhost:8080/actuator/health`

## Task API

In-memory CRUD (no database).

| Method | Path | Notes |
| --- | --- | --- |
| `GET` | `/api/tasks` | List |
| `GET` | `/api/tasks/{id}` | `404` if missing |
| `POST` | `/api/tasks` | `201`; title required |
| `PUT` | `/api/tasks/{id}` | Full update |
| `DELETE` | `/api/tasks/{id}` | `204` |

Validation and malformed JSON return `400`. Unknown ids return `404`.

## Opt-in failure scenarios

Default `mvn test` and `mvn package` stay green. Scenario tests run only when selected; they fail Surefire with a non-zero Maven exit.

| Scenario | Maven | Jenkins `FAIL_SCENARIO` |
| --- | --- | --- |
| Green build | `mvn test` | `none` |
| Assertion failure | `mvn test -Dfail.scenario=assertion` or `-Pfail-assertion` | `assertion` |
| Java exception + stack trace | `mvn test -Dfail.scenario=exception` or `-Pfail-exception` | `exception` |
| Bean validation (HTTP 400 vs expected 201) | `mvn test -Dfail.scenario=validation` or `-Pfail-validation` | `validation` |
| Simulated dependency HTTP 503 | `mvn test -Dfail.scenario=http5xx` or `-Pfail-http5xx` | `http5xx` |

## Jenkins job

1. Put this project in Git (or any SCM Jenkins can clone). Do not point the job at an absolute folder path in the Jenkinsfile; checkout uses `checkout scm` into the workspace.
2. New Item → **Pipeline**.
3. Pipeline definition: **Pipeline script from SCM**.
4. Repository URL: this project. Script Path: `Jenkinsfile`.
5. Configure a Jenkins Maven tool named **`Maven-3.9.11`** (Manage Jenkins → Tools → Maven installations). Automatic install is fine. The pipeline uses this name by default; you do not need `MAVEN_HOME` / `MAVEN_TOOL` on the build form.

   Optional overrides: job/env `MAVEN_HOME` (install dir or `bin`) or `MAVEN_TOOL` (another Global Tool name). Those win over `Maven-3.9.11`.

Build with parameters:

- `FAIL_SCENARIO` — `none` for success, or a failure mode above.
- `MAVEN_HOME` / `MAVEN_TOOL` — optional overrides (also reads env). Defaults to tool `Maven-3.9.11`.
- `LOG_INGEST_URL` — optional. Empty means **do not upload**.
- `LOG_INGEST_CREDENTIAL_ID` — optional Secret Text credential ID.

The same names can be set as job or folder **environment variables** if you prefer not to type them each build.

### Credential creation

Only needed if the target backend requires a bearer token.

1. Manage Jenkins → Credentials → (global or folder) → **Add Credentials**.
2. Kind: **Secret text**.
3. **ID**: any id you choose (example: `log-ingest-token`). Put that same id in `LOG_INGEST_CREDENTIAL_ID`.
4. Secret: the backend token.

Never put the token in the Jenkinsfile, README, or job script. The pipeline binds it as `LOG_INGEST_TOKEN` and does not echo it. Jenkins secret masking still applies.

### Success and failure testing

- `FAIL_SCENARIO=none` → Maven test + package should be green. Console log is still captured.
- `FAIL_SCENARIO=assertion` (or `exception` / `validation` / `http5xx`) → Maven fails, Jenkins build is **FAILURE**, console still captured and upload still attempted if configured.
- Leave `LOG_INGEST_URL` empty → upload skipped; Maven result unchanged.
- Point `LOG_INGEST_URL` at a closed port or a URL that returns 4xx/5xx → pipeline prints `[log-forward] POST finished with HTTP … (non-fatal)` and **does not** change SUCCESS/FAILURE.

## Log upload contract

This project does not assume a host, port, path, database, or JSON response shape. If `LOG_INGEST_URL` is set, Jenkins sends:

- Method: `POST`
- Content type: `multipart/form-data`
- Optional header: `Authorization: Bearer <secret>` when `LOG_INGEST_CREDENTIAL_ID` is set
- File part: the current Jenkins console text from `${BUILD_URL}consoleText` (workspace file `jenkins-console-<build>.log`)

Default form field names:

| Field | Value |
| --- | --- |
| `job_name` | Jenkins `JOB_NAME` |
| `build_number` | Jenkins `BUILD_NUMBER` |
| `build_status` | `SUCCESS`, `FAILURE`, `UNSTABLE`, or `ABORTED` |
| `build_url` | Jenkins `BUILD_URL` |
| `repo_url` | `GIT_URL` when Git checkout sets it |
| `commit_sha` | `GIT_COMMIT` when Git checkout sets it |
| `file` | Console log file upload |

Rename fields with parameters / env vars `LOG_FIELD_JOB_NAME`, `LOG_FIELD_BUILD_NUMBER`, `LOG_FIELD_BUILD_STATUS`, `LOG_FIELD_BUILD_URL`, `LOG_FIELD_REPO_URL`, `LOG_FIELD_COMMIT_SHA`, `LOG_FIELD_FILE`.

HTTP 2xx is a successful delivery. Any other code, connection error, missing URL, or missing/invalid credential is logged and ignored for the build result. Response bodies are not parsed.

### Adapting to a backend

Point `LOG_INGEST_URL` at that API’s ingest endpoint. If it expects different field names, set the `LOG_FIELD_*` parameters. If it expects JSON instead of multipart, add a small adapter in front of the API (this Jenkinsfile stays multipart). If it uses `X-API-Key` instead of `Authorization: Bearer`, change only the header line in `postConsoleLog()` in the Jenkinsfile.

## Troubleshooting

| Symptom | What to check |
| --- | --- |
| Job cannot find `pom.xml` | Script Path is `Jenkinsfile` at the repo root; SCM checkout succeeded. |
| `mvn` is not recognized | The Global Tool name must be exactly `Maven-3.9.11` (or set `MAVEN_TOOL` / `MAVEN_HOME`). Jenkins parameters from a new Jenkinsfile appear after one run; the default tool no longer depends on those fields. |
| Console file is a one-line fallback | Agent must GET `${BUILD_URL}consoleText` (same Jenkins, curl.exe on PATH). Restricting anonymous read of job logs can block this. |
| Upload skipped | `LOG_INGEST_URL` blank on both the parameter and the job env. |
| `Credentials not found` | `LOG_INGEST_CREDENTIAL_ID` must match a Secret Text credential visible to the job. Upload failure is non-fatal. |
| Token in console | Do not `echo` the credential. Recreate the credential if it leaked. |
| Failed Maven build flipped to SUCCESS | Should not happen; upload scripts always `exit 0` and `catchError` keeps the original result. |

A live Jenkins controller is required to confirm checkout, Windows `bat`/`powershell`, consoleText capture, and ingest. `mvn test` / `mvn package` only verify the Spring Boot app and Jenkinsfile text contract.
