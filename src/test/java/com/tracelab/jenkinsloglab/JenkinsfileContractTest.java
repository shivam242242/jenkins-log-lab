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
    void avoidsBackendSpecificAndUnsafeAssumptions() {
        String lower = jenkinsfile.toLowerCase();
        assertFalse(lower.contains("trace"));
        assertFalse(jenkinsfile.contains("rawBuild"));
        assertFalse(jenkinsfile.contains("C:\\\\"));
        assertFalse(jenkinsfile.contains("/Users/"));
        assertFalse(jenkinsfile.contains("127.0.0.1"));
        assertFalse(jenkinsfile.contains("localhost"));
        assertFalse(lower.contains("password"));
        assertFalse(jenkinsfile.contains("Bearer trc_"));
        assertTrue(jenkinsfile.contains("bat "));
        assertTrue(jenkinsfile.contains("non-fatal"));
        assertTrue(jenkinsfile.contains("MAVEN_HOME"));
        assertTrue(jenkinsfile.contains("MAVEN_TOOL"));
        assertTrue(jenkinsfile.contains("PATH+MAVEN"));
        assertFalse(jenkinsfile.contains(".m2\\\\wrapper"));
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
