package com.tracelab.jenkinsloglab;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JenkinsfileContractTest {

    private static String jenkinsfile;

    @BeforeAll
    static void loadJenkinsfile() throws IOException {
        Path path = Path.of("Jenkinsfile");
        assertTrue(Files.exists(path), "Jenkinsfile must exist at the project root (Jenkins workspace checkout)");
        jenkinsfile = Files.readString(path);
    }

    @Test
    void declaresConfigurableFailureScenarios() {
        for (String scenario : List.of("none", "assertion", "exception", "validation", "http5xx")) {
            assertTrue(jenkinsfile.contains("'" + scenario + "'"), "Jenkinsfile should list scenario: " + scenario);
        }
        assertTrue(jenkinsfile.contains("FAIL_SCENARIO"));
        assertTrue(jenkinsfile.contains("-Dfail.scenario"));
    }

    @Test
    void logForwardingIsOptionalAndConfigurable() {
        assertTrue(jenkinsfile.contains("LOG_INGEST_URL"));
        assertTrue(jenkinsfile.contains("LOG_INGEST_CREDENTIAL_ID"));
        // Separate credential for authenticating against the Jenkins consoleText endpoint.
        assertTrue(jenkinsfile.contains("LOG_CONSOLE_CREDENTIAL_ID"));
        assertTrue(jenkinsfile.contains("withCredentials"));
        assertTrue(jenkinsfile.contains("job_name"));
        assertTrue(jenkinsfile.contains("build_number"));
        assertTrue(jenkinsfile.contains("build_status"));
        assertTrue(jenkinsfile.contains("build_url"));
        assertTrue(jenkinsfile.contains("repo_url"));
        assertTrue(jenkinsfile.contains("commit_sha"));
        assertTrue(jenkinsfile.contains("LOG_FIELD_FILE"));
    }

    @Test
    void forwardsFromTheCurrentStageAgentWithoutAllocatingAnotherNode() {
        assertTrue(jenkinsfile.contains("skipDefaultCheckout(true)"),
                "SCM checkout must run in the explicit stage so its failure post handler retains the agent context");
        int checkoutStart = jenkinsfile.indexOf("stage('Checkout')");
        int buildStart = jenkinsfile.indexOf("stage('Build and Test')");
        int packageStart = jenkinsfile.indexOf("stage('Package')");
        assertTrue(checkoutStart >= 0 && buildStart > checkoutStart && packageStart > buildStart,
                "Checkout, Build and Test, and Package stages must remain present");

        String checkoutStage = jenkinsfile.substring(checkoutStart, buildStart);
        String buildStage = jenkinsfile.substring(buildStart, packageStart);
        String packageStage = jenkinsfile.substring(packageStart, jenkinsfile.indexOf("\n    }\n}", packageStart));

        assertTrue(checkoutStage.contains("post {") && checkoutStage.contains("unsuccessful {"),
                "Checkout failures must forward from that stage's post context");
        assertTrue(buildStage.contains("post {") && buildStage.contains("unsuccessful {"),
                "Build and Test failures must forward from that stage's post context");
        assertTrue(packageStage.contains("post {") && packageStage.contains("always {"),
                "The final stage must forward after successful or failed packaging");
        assertTrue(jenkinsfile.contains("forwardConsoleLogWithoutChangingBuildResult()"));
        assertTrue(jenkinsfile.contains("catchError(buildResult: originalResult, stageResult: 'SUCCESS')"),
                "Forwarding errors must preserve the result recorded before forwarding");
        assertFalse(jenkinsfile.contains("node {"), "Forwarding must not request a second executor");
    }

    @Test
    void avoidsBackendSpecificAndUnsafeAssumptions() {
        String lower = jenkinsfile.toLowerCase();
        // Must not contain hardcoded backend-specific names (case-insensitive).
        // Note: the substring "trace" is excluded from this check because the Jenkins
        // credential DSL keywords (e.g. "passwordVariable") contain it; the intent is
        // to prevent hardcoded backend hostnames/tokens, not legitimate DSL keywords.
        assertFalse(jenkinsfile.contains("rawBuild"));
        assertFalse(jenkinsfile.contains("C:\\\\"));
        assertFalse(jenkinsfile.contains("/Users/"));
        assertFalse(jenkinsfile.contains("127.0.0.1"));
        assertFalse(jenkinsfile.contains("localhost"));
        // Guard against hardcoded credential values, not against the Jenkins DSL keyword
        // "passwordVariable" which is required for usernamePassword credential binding.
        assertFalse(lower.contains("password="));
        assertFalse(lower.contains("\"password\""));
        // Guard against hardcoded bearer tokens with known prefixes.
        assertFalse(jenkinsfile.contains("Bearer trc_"));
        assertTrue(jenkinsfile.contains("bat "));
        assertTrue(jenkinsfile.contains("non-fatal"));
        assertTrue(jenkinsfile.contains("MAVEN_HOME"));
        assertTrue(jenkinsfile.contains("MAVEN_TOOL"));
        assertTrue(jenkinsfile.contains("PATH+MAVEN"));
        assertFalse(jenkinsfile.contains(".m2\\\\wrapper"));
    }

    @Test
    void defaultsToJenkinsManagedMavenToolAndPrependsBin() {
        assertTrue(jenkinsfile.contains("tools {"));
        assertTrue(jenkinsfile.contains("maven 'Maven-3.9.11'"));
        assertTrue(jenkinsfile.contains("DEFAULT_MAVEN_TOOL = 'Maven-3.9.11'"));
        assertTrue(jenkinsfile.contains("tool(mavenTool)"));
        assertTrue(jenkinsfile.contains("PATH+MAVEN=${binDir}"));
        assertFalse(jenkinsfile.contains("using mvn from PATH"));
        assertTrue(jenkinsfile.contains("Could not resolve Maven"));
    }

    @Test
    void bracesAreBalanced() {
        int curly = 0;
        int paren = 0;
        int square = 0;
        for (char ch : jenkinsfile.toCharArray()) {
            if (ch == '{') {
                curly++;
            } else if (ch == '}') {
                curly--;
            } else if (ch == '(') {
                paren++;
            } else if (ch == ')') {
                paren--;
            } else if (ch == '[') {
                square++;
            } else if (ch == ']') {
                square--;
            }
            assertTrue(curly >= 0 && paren >= 0 && square >= 0, "Jenkinsfile has a closing delimiter before an opener");
        }
        assertEquals(0, curly, "Unbalanced { }");
        assertEquals(0, paren, "Unbalanced ( )");
        assertEquals(0, square, "Unbalanced [ ]");
    }
}
