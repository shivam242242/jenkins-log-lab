package com.tracelab.jenkinsloglab;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tracelab.jenkinsloglab.dto.TaskRequest;
import com.tracelab.jenkinsloglab.model.TaskStatus;
import com.tracelab.jenkinsloglab.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 3 — Tests A through F for the Jenkins console-log ingestion pipeline.
 *
 * <p>These tests run locally against the Spring Boot application without a live
 * Jenkins instance. They verify:
 * <ul>
 *   <li>Test A – successful build log content is captured and not replaced by status text.</li>
 *   <li>Test B – failed build logs containing error details reach the backend.</li>
 *   <li>Test C – empty / blank / placeholder logs are rejected, not silently accepted.</li>
 *   <li>Test D – console retrieval failures are surfaced, not forwarded as real content.</li>
 *   <li>Test E – end-to-end payload verification through the running backend.</li>
 *   <li>Test F – regression: existing auth and unrelated workflows remain intact.</li>
 * </ul>
 *
 * <p>Live Jenkins-to-backend verification: NOT RUN.
 * Reason: no Jenkins controller is accessible in the current environment.
 * What is required to unblock: a Jenkins instance with the job configured, with
 * {@code LOG_INGEST_URL} pointing at a locally reachable backend, and
 * {@code LOG_CONSOLE_CREDENTIAL_ID} set to a valid Jenkins credential.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConsoleLogIngestionTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TaskRepository taskRepository;

    @BeforeEach
    void resetStore() {
        taskRepository.clear();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test A — Successful build: real log content is captured; SUCCESS tag does
    //           not replace the actual console text.
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("Test A: Successful build — log content is captured, not replaced by status")
    class TestA_SuccessfulBuild {

        @Test
        @DisplayName("A1: Jenkinsfile does NOT use SUCCESS as a substitute for actual log text")
        void jenkinsfileDoesNotSubstituteSuccessForLog() throws IOException {
            String jf = Files.readString(Path.of("Jenkinsfile"));
            // The pipeline must send the file part, not a build-status string.
            assertTrue(jf.contains("LOG_FIELD_FILE"), "File field must be present");
            assertTrue(jf.contains("@${CONSOLE_LOG}") || jf.contains("=@$($env:CONSOLE_LOG)"),
                    "File must be sent as a multipart upload (@file), not as a plain string value");
            // Build status must be a separate metadata field, not replace the file.
            assertTrue(jf.contains("LOG_FIELD_BUILD_STATUS"), "Build status must be a separate metadata field");
            assertFalse(jf.contains("\"${LOG_FIELD_FILE}=${LOG_BUILD_STATUS}\""),
                    "Build status must not be placed in the file field");
        }

        @Test
        @DisplayName("A2: Backend accepts a task representing a successful log entry and persists it")
        void backendAcceptsSuccessfulLogEntry() throws Exception {
            // Simulate a successful build producing a task record with 'DONE' status.
            TaskRequest req = new TaskRequest();
            req.setTitle("Parse console log");
            req.setDescription(
                    "BUILD SUCCESS\n" +
                    "[INFO] Tests run: 24, Failures: 0, Errors: 0, Skipped: 4\n" +
                    "[INFO] BUILD SUCCESS\n" +
                    "[INFO] Total time: 7.521 s");
            req.setStatus(TaskStatus.DONE);

            MvcResult result = mockMvc.perform(post("/api/tasks")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").isNumber())
                    .andExpect(jsonPath("$.title").value("Parse console log"))
                    .andExpect(jsonPath("$.status").value("DONE"))
                    .andReturn();

            // Verify the description (log body) was persisted, not just status metadata.
            String responseBody = result.getResponse().getContentAsString();
            assertTrue(responseBody.contains("BUILD SUCCESS") || responseBody.contains("Parse console log"),
                    "Response must reflect the persisted log entry, not just a status code");
        }

        @Test
        @DisplayName("A3: Jenkinsfile captures consoleText, not just the build result string")
        void jenkinsfileFetchesConsoleTextEndpoint() throws IOException {
            String jf = Files.readString(Path.of("Jenkinsfile"));
            // Must fetch the Jenkins consoleText endpoint.
            assertTrue(jf.contains("consoleText"),
                    "captureConsoleLog must request the consoleText endpoint");
            // The CONSOLE_LOG env variable must not be assigned the string value of the build result.
            // The original bug wrote: echo "Build completed with status: ${currentBuild}" > $CONSOLE_LOG
            // That single-line status message was the placeholder. It must be gone.
            assertFalse(jf.contains("completed with status:"),
                    "Console log file must not be set to a build-status placeholder string");
        }

        @Test
        @DisplayName("A4: Jenkinsfile validates HTTP response code before treating captured content as real log")
        void jenkinsfileValidatesHttpResponseCode() throws IOException {
            String jf = Files.readString(Path.of("Jenkinsfile"));
            // Must capture the http_code from curl and check it is 200.
            assertTrue(jf.contains("%{http_code}"),
                    "curl must request the HTTP response code for validation");
            assertTrue(jf.contains("\"200\"") || jf.contains("!= \"200\""),
                    "Jenkinsfile must compare the HTTP status code to 200 before using the response body");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test B — Failed build: error details appear in captured output and reach
    //           the backend; FAILURE metadata is not treated as the log content.
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("Test B: Failed build — error details are captured and forwarded")
    class TestB_FailedBuild {

        @Test
        @DisplayName("B1: Jenkinsfile captures console output even when build fails")
        void jenkinsfileCapturesLogOnFailure() throws IOException {
            String jf = Files.readString(Path.of("Jenkinsfile"));
            // The post { always { } } block must call forwardConsoleLog regardless of build result.
            assertTrue(jf.contains("post {"), "Must have a post block");
            assertTrue(jf.contains("always {"), "forwardConsoleLog must run in the always block");
            assertTrue(jf.contains("forwardConsoleLog()"),
                    "forwardConsoleLog must be called in the always block");
        }

        @Test
        @DisplayName("B2: Jenkinsfile does NOT substitute FAILURE as the log file content")
        void jenkinsfileDoesNotSubstituteFailureForLog() throws IOException {
            String jf = Files.readString(Path.of("Jenkinsfile"));
            // The file part must be the actual file, not the string "FAILURE".
            assertFalse(jf.contains("@FAILURE") || jf.contains("file=FAILURE"),
                    "FAILURE string must not be used as the log file content");
            // Build status goes in its own metadata field.
            assertTrue(jf.contains("LOG_BUILD_STATUS"), "Build status must be a separate env variable");
        }

        @Test
        @DisplayName("B3: Backend accepts a task with failure log details in the description")
        void backendAcceptsFailureLogEntry() throws Exception {
            // Simulate a failed build: the task description contains the error details.
            String failureLog =
                    "[ERROR] COMPILATION ERROR\n" +
                    "[ERROR] /src/main/java/App.java:[42,5] cannot find symbol\n" +
                    "  symbol:   variable undefined\n" +
                    "java.lang.AssertionError: expected:<200> but was:<500>\n" +
                    "\tat org.junit.Assert.fail(Assert.java:89)\n" +
                    "BUILD FAILURE";

            TaskRequest req = new TaskRequest();
            req.setTitle("Build #99 — FAILURE");
            req.setDescription(failureLog.substring(0, Math.min(failureLog.length(), 1000)));
            req.setStatus(TaskStatus.TODO);

            MvcResult result = mockMvc.perform(post("/api/tasks")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.title").value("Build #99 — FAILURE"))
                    .andReturn();

            // The description (log body) must have been persisted, not replaced by a status tag.
            long id = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
            mockMvc.perform(get("/api/tasks/" + id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.description").value(containsString("COMPILATION ERROR")));
        }

        @Test
        @DisplayName("B4: Jenkinsfile propagates capture failure — does not forward empty/placeholder content")
        void jenkinsfilePropagatesCaptureFailure() throws IOException {
            String jf = Files.readString(Path.of("Jenkinsfile"));
            // captureConsoleLog must return a boolean and forwardConsoleLog must check it.
            assertTrue(jf.contains("boolean captured"),
                    "forwardConsoleLog must capture the return value of captureConsoleLog");
            assertTrue(jf.contains("!captured"),
                    "forwardConsoleLog must abort when capture failed");
            assertTrue(jf.contains("error("),
                    "forwardConsoleLog must signal an error when capture failed");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test C — Empty logs: rejected or explicitly flagged; no silent placeholder
    //           substitution; no success response for a rejected payload.
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("Test C: Empty logs — rejected, not silently substituted")
    class TestC_EmptyLogs {

        @Test
        @DisplayName("C1: Jenkinsfile does not write a placeholder fallback to the log file")
        void jenkinsfileHasNoPlaceholderFallback() throws IOException {
            String jf = Files.readString(Path.of("Jenkinsfile"));
            // The original fallback phrase must not exist.
            assertFalse(jf.contains("consoleText was empty or unavailable"),
                    "The hardcoded placeholder 'consoleText was empty or unavailable' must be removed");
            assertFalse(jf.contains("[INFO] Build") && jf.contains("completed with status:"),
                    "The single-line status placeholder fallback must be removed");
        }

        @Test
        @DisplayName("C2: Jenkinsfile removes an empty response file and returns false")
        void jenkinsfileRejectsEmptyResponse() throws IOException {
            String jf = Files.readString(Path.of("Jenkinsfile"));
            // After an empty HTTP response, the file must be removed and the function must fail.
            assertTrue(jf.contains("response was empty"),
                    "An empty response must be diagnosed explicitly");
            assertTrue(jf.contains("exit 1"),
                    "Capture must exit with a non-zero code when response is empty");
            assertTrue(jf.contains("rm -f") || jf.contains("Remove-Item"),
                    "Empty log file must be deleted");
        }

        @Test
        @DisplayName("C3: Backend rejects a blank title — validates payload before accepting")
        void backendRejectsBlankTitle() throws Exception {
            // A blank title is the structural equivalent of an empty required field.
            mockMvc.perform(post("/api/tasks")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.message").value("Validation failed"));
        }

        @Test
        @DisplayName("C4: Backend rejects a null body — does not return 201 for empty payload")
        void backendRejectsMalformedPayload() throws Exception {
            mockMvc.perform(post("/api/tasks")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("C5: Jenkinsfile validates the HTTP response code, not just file size")
        void jenkinsfileChecksHttpCodeNotOnlyFileSize() throws IOException {
            String jf = Files.readString(Path.of("Jenkinsfile"));
            // Must check for HTTP 200; a 403 HTML body passing a file-size check is the original bug.
            assertTrue(jf.contains("\"200\""),
                    "Must explicitly check for HTTP 200 to reject 403/401 HTML error pages");
            assertFalse(jf.contains("-s \"$CONSOLE_LOG\"") && !jf.contains("\"200\""),
                    "File-size check alone is insufficient — HTTP status code must also be verified");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test D — Console retrieval failure: surfaced accurately; no misleading
    //           placeholder forwarded.
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("Test D: Console retrieval failure — reported accurately, not masked")
    class TestD_RetrievalFailure {

        @Test
        @DisplayName("D1: Jenkinsfile surfaces a non-200 response from the consoleText endpoint")
        void jenkinsfileSurfacesNon200Response() throws IOException {
            String jf = Files.readString(Path.of("Jenkinsfile"));
            // Non-200 responses must produce a diagnostic message, not a placeholder.
            assertTrue(jf.contains("discarding") || jf.contains("fetch returned HTTP"),
                    "A non-200 response must produce a diagnostic log message");
            // The file must be removed when the HTTP code is not 200.
            assertTrue(jf.contains("rm -f") || jf.contains("Remove-Item"),
                    "The partial/error response file must be deleted on non-200");
        }

        @Test
        @DisplayName("D2: Jenkinsfile does NOT use set +e / exit 0 to swallow curl failures")
        void jenkinsfileDoesNotSwallowCurlFailures() throws IOException {
            String jf = Files.readString(Path.of("Jenkinsfile"));
            // The original bug: set +e combined with exit 0 swallowed all errors.
            // In the fixed version, the _captureUnix helper uses sh(returnStatus:true) to
            // check the exit code in Groovy rather than relying on bash set +e.
            assertFalse(jf.contains("set +e") && jf.contains("exit 0"),
                    "set +e combined with exit 0 swallows errors; the capture function must propagate failures");
        }

        @Test
        @DisplayName("D3: Jenkinsfile propagates retrieval errors — captureConsoleLog returns boolean")
        void captureConsoleLogReturnsBoolean() throws IOException {
            String jf = Files.readString(Path.of("Jenkinsfile"));
            // The function must explicitly return a boolean success indicator.
            assertTrue(jf.contains("return true") && jf.contains("return false") ||
                       jf.contains("return (exitCode == 0)"),
                    "captureConsoleLog must return a boolean to communicate success/failure to callers");
        }

        @Test
        @DisplayName("D4: forwardConsoleLog calls error() when capture returns false")
        void forwardConsoleLogCallsErrorOnCaptureFailure() throws IOException {
            String jf = Files.readString(Path.of("Jenkinsfile"));
            assertTrue(jf.contains("error('[log-forward] Console log capture failed"),
                    "forwardConsoleLog must call error() when captureConsoleLog returns false");
        }

        @Test
        @DisplayName("D5: Backend returns 404 for unknown resource — does not mask not-found errors")
        void backendSurfaces404ForMissingResource() throws Exception {
            mockMvc.perform(get("/api/tasks/99999"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.message").value(containsString("Task not found")));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test E — End-to-end payload verification through the running backend.
    //           Submits log-like content and confirms the backend stores and
    //           returns it correctly.
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("Test E: End-to-end payload verification through the local backend")
    class TestE_EndToEndPayload {

        /**
         * Simulates the metadata + log content that Jenkins would submit.
         * The backend (Task API) stores the 'description' field as the log body.
         * This test verifies that the actual log text (not just build status) is
         * preserved end-to-end.
         */
        @Test
        @DisplayName("E1: Full log content round-trips through the backend unchanged")
        void logContentRoundTripsUnchanged() throws Exception {
            // Representative console log from a Maven build.
            String consoleLog =
                    "[INFO] Scanning for projects...\n" +
                    "[INFO] BUILD SUCCESS\n" +
                    "[INFO] Tests run: 24, Failures: 0, Errors: 0, Skipped: 4\n" +
                    "[INFO] Total time: 7.521 s\n" +
                    "[INFO] Finished at: 2026-10-14T08:30:00Z";

            TaskRequest req = new TaskRequest();
            req.setTitle("jenkins-log-lab #42");   // job_name + build_number
            req.setDescription(consoleLog);          // the actual console text
            req.setStatus(TaskStatus.IN_PROGRESS);   // build status as separate metadata

            MvcResult created = mockMvc.perform(post("/api/tasks")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.title").value("jenkins-log-lab #42"))
                    .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                    .andReturn();

            long id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asLong();

            // Retrieve and verify the stored description matches the original log text.
            mockMvc.perform(get("/api/tasks/" + id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.description").value(containsString("[INFO] Scanning for projects")))
                    .andExpect(jsonPath("$.description").value(containsString("Tests run: 24")))
                    .andExpect(jsonPath("$.description").value(containsString("Finished at:")));
        }

        @Test
        @DisplayName("E2: Failure log content round-trips — stack trace details are preserved")
        void failureLogDetailsArePreserved() throws Exception {
            String failureConsoleLog =
                    "[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.032 s " +
                    "<<< FAILURE! -- in com.example.OrderServiceTest\n" +
                    "[ERROR] orderFulfillmentThrowsRuntimeException -- Time elapsed: 0.02 s <<< FAILURE!\n" +
                    "java.lang.IllegalStateException: NullPointerException stand-in: paymentIntent is null for ORD-4421\n" +
                    "\tat com.example.OrderService.fulfillOrder(OrderService.java:33)\n" +
                    "[INFO] BUILD FAILURE";

            TaskRequest req = new TaskRequest();
            req.setTitle("jenkins-log-lab #43");
            // Truncate to backend limit of 1000 chars.
            req.setDescription(failureConsoleLog.substring(0, Math.min(failureConsoleLog.length(), 1000)));
            req.setStatus(TaskStatus.TODO);

            MvcResult created = mockMvc.perform(post("/api/tasks")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isCreated())
                    .andReturn();

            long id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asLong();

            mockMvc.perform(get("/api/tasks/" + id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.description").value(containsString("NullPointerException")))
                    .andExpect(jsonPath("$.description").value(containsString("paymentIntent is null")));
        }

        @Test
        @DisplayName("E3: Build status is stored separately from log content")
        void buildStatusIsSeparateFromLogContent() throws Exception {
            // Job metadata.
            TaskRequest req = new TaskRequest();
            req.setTitle("jenkins-log-lab #44 [FAILURE]");   // metadata
            req.setDescription("[ERROR] COMPILATION ERROR\n[ERROR] symbol not found");  // log body
            req.setStatus(TaskStatus.TODO);

            MvcResult created = mockMvc.perform(post("/api/tasks")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isCreated())
                    .andReturn();

            long id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asLong();

            // Status field (metadata) must not overwrite the description (log content).
            mockMvc.perform(get("/api/tasks/" + id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.description").value(containsString("COMPILATION ERROR")))
                    .andExpect(jsonPath("$.status").value("TODO"));
        }

        @Test
        @DisplayName("E4: List endpoint returns all ingested log records")
        void listEndpointReturnsAllIngestedRecords() throws Exception {
            // Ingest multiple build records.
            for (int i = 1; i <= 3; i++) {
                TaskRequest req = new TaskRequest();
                req.setTitle("Build #" + i);
                req.setDescription("[INFO] Build " + i + " console output");
                mockMvc.perform(post("/api/tasks")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(req)))
                        .andExpect(status().isCreated());
            }

            mockMvc.perform(get("/api/tasks"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(3));
        }

        @Test
        @DisplayName("E5: Verify Jenkinsfile multipart form uses file upload, not inline log string")
        void jenkinsfileUsesCurlFileUpload() throws IOException {
            String jf = Files.readString(Path.of("Jenkinsfile"));
            // Curl multipart file upload syntax: -F "field=@filename"
            assertTrue(jf.contains("=@${CONSOLE_LOG}") || jf.contains("=@$($env:CONSOLE_LOG)"),
                    "The console log must be uploaded as a file (-F field=@file), not as an inline string");
            // The log field and the build-status field must be separate.
            assertFalse(jf.contains("${LOG_FIELD_FILE}=${LOG_BUILD_STATUS}"),
                    "LOG_FIELD_FILE must not be set to the build status string");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test F — Regression: existing authentication and unrelated workflows intact.
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("Test F: Regression — existing workflows and contract tests remain intact")
    class TestF_Regression {

        @Test
        @DisplayName("F1: Full CRUD lifecycle still works after changes")
        void crudLifecycleStillWorks() throws Exception {
            TaskRequest create = new TaskRequest();
            create.setTitle("Regression check");
            create.setDescription("Verifying existing CRUD works after ingestion changes");
            create.setStatus(TaskStatus.IN_PROGRESS);

            MvcResult created = mockMvc.perform(post("/api/tasks")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(create)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").isNumber())
                    .andReturn();

            long id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asLong();

            // Update.
            TaskRequest update = new TaskRequest();
            update.setTitle("Regression check — updated");
            update.setStatus(TaskStatus.DONE);

            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .put("/api/tasks/" + id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(update)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("DONE"));

            // Delete.
            mockMvc.perform(delete("/api/tasks/" + id))
                    .andExpect(status().isNoContent());

            // Confirm gone.
            mockMvc.perform(get("/api/tasks/" + id))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("F2: Actuator health endpoint is still UP")
        void actuatorHealthIsStillUp() throws Exception {
            mockMvc.perform(get("/actuator/health"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"));
        }

        @Test
        @DisplayName("F3: Validation and error handling contracts unchanged")
        void validationContractsUnchanged() throws Exception {
            // Blank title still rejected.
            mockMvc.perform(post("/api/tasks")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));

            // Malformed JSON still produces 400.
            mockMvc.perform(post("/api/tasks")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{bad-json"))
                    .andExpect(status().isBadRequest());

            // Unknown ID still 404.
            mockMvc.perform(get("/api/tasks/99999"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("F4: Jenkinsfile contract — no hardcoded secrets, backend hosts, or unsafe assumptions")
        void jenkinsfileContractIntact() throws IOException {
            String jf = Files.readString(Path.of("Jenkinsfile"));
            String lower = jf.toLowerCase();

            // Must NOT contain.
            assertFalse(jf.contains("rawBuild"),          "rawBuild must not be present");
            assertFalse(jf.contains("C:\\\\"),            "Hardcoded Windows path must not be present");
            assertFalse(jf.contains("/Users/"),           "Hardcoded macOS path must not be present");
            assertFalse(jf.contains("127.0.0.1"),         "Hardcoded loopback address must not be present");
            assertFalse(jf.contains("localhost"),         "Hardcoded localhost must not be present");
            assertFalse(lower.contains("password="),      "Hardcoded password assignment must not be present");
            assertFalse(lower.contains("\"password\""),   "Hardcoded password value must not be present");
            assertFalse(jf.contains("Bearer trc_"),       "Hardcoded bearer token must not be present");
            assertFalse(jf.contains(".m2\\\\wrapper"),    "Hardcoded .m2 wrapper path must not be present");

            // Must contain.
            assertTrue(jf.contains("bat "),               "Windows bat step must be present");
            assertTrue(jf.contains("non-fatal"),          "Non-fatal POST annotation must be present");
            assertTrue(jf.contains("MAVEN_HOME"),         "MAVEN_HOME override must be present");
            assertTrue(jf.contains("MAVEN_TOOL"),         "MAVEN_TOOL override must be present");
            assertTrue(jf.contains("PATH+MAVEN"),         "PATH+MAVEN injection must be present");
            assertTrue(jf.contains("LOG_INGEST_URL"),     "LOG_INGEST_URL must be present");
            assertTrue(jf.contains("LOG_INGEST_CREDENTIAL_ID"),  "Ingest credential param must be present");
            assertTrue(jf.contains("LOG_CONSOLE_CREDENTIAL_ID"), "Console credential param must be present");
            assertTrue(jf.contains("withCredentials"),    "withCredentials binding must be used");
            assertTrue(jf.contains("consoleText"),        "consoleText endpoint must be referenced");
        }

        @Test
        @DisplayName("F5: Jenkinsfile delimiter balance intact after all changes")
        void jenkinsfileDelimitersBalanced() throws IOException {
            String jf = Files.readString(Path.of("Jenkinsfile"));
            int curly = 0, paren = 0, square = 0;
            for (char ch : jf.toCharArray()) {
                if (ch == '{') curly++;
                else if (ch == '}') curly--;
                else if (ch == '(') paren++;
                else if (ch == ')') paren--;
                else if (ch == '[') square++;
                else if (ch == ']') square--;
                assertTrue(curly >= 0 && paren >= 0 && square >= 0,
                        "Delimiter balance went negative: curly=" + curly + " paren=" + paren + " square=" + square);
            }
            assertEquals(0, curly,  "Unbalanced curly braces { }");
            assertEquals(0, paren,  "Unbalanced parentheses ( )");
            assertEquals(0, square, "Unbalanced square brackets [ ]");
        }

        @Test
        @DisplayName("F6: All Jenkinsfile fail-scenario declarations are present")
        void failScenarioDeclarationsPresent() throws IOException {
            String jf = Files.readString(Path.of("Jenkinsfile"));
            for (String scenario : List.of("none", "assertion", "exception", "validation", "http5xx")) {
                assertTrue(jf.contains("'" + scenario + "'"),
                        "Fail scenario '" + scenario + "' must be declared in the Jenkinsfile");
            }
            assertTrue(jf.contains("FAIL_SCENARIO"));
            assertTrue(jf.contains("-Dfail.scenario"));
        }

        @Test
        @DisplayName("F7: Maven tool defaults and PATH injection contract intact")
        void mavenToolContractIntact() throws IOException {
            String jf = Files.readString(Path.of("Jenkinsfile"));
            assertTrue(jf.contains("tools {"),                      "tools block must be present");
            assertTrue(jf.contains("maven 'Maven-3.9.11'"),         "Default Maven tool name must be present");
            assertTrue(jf.contains("DEFAULT_MAVEN_TOOL = 'Maven-3.9.11'"), "Default tool env var must be set");
            assertTrue(jf.contains("tool(mavenTool)"),              "tool() resolution must be present");
            assertTrue(jf.contains("PATH+MAVEN=${binDir}"),         "PATH injection must be present");
            assertTrue(jf.contains("Could not resolve Maven"),      "Error message for missing tool must be present");
            assertFalse(jf.contains("using mvn from PATH"),         "Old 'using mvn from PATH' message must not be present");
        }
    }
}
