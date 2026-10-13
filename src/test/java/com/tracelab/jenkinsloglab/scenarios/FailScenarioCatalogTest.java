package com.tracelab.jenkinsloglab.scenarios;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledIfSystemProperties;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FailScenarioCatalogTest {

    static final Set<String> SUPPORTED = Set.of("none", "assertion", "exception", "validation", "http5xx");

    @Test
    void defaultBuildDoesNotSelectAFailureScenario() {
        String selected = System.getProperty("fail.scenario", "");
        assumeTrue(selected == null || selected.isBlank() || selected.equals("none"));
        assertTrue(selected == null || selected.isBlank() || selected.equals("none"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"assertion", "exception", "validation", "http5xx"})
    void pomDefinesMavenProfilesForEachFailure(String scenario) throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));
        assertTrue(pom.contains("<id>fail-" + scenario + "</id>"));
        assertTrue(pom.contains("<fail.scenario>" + scenario + "</fail.scenario>"));
        assertTrue(SUPPORTED.contains(scenario));
    }

    @Test
    void scenarioTestClassesStayDisabledUnlessSelected() {
        assertTrue(AssertionFailureScenarioTest.class.isAnnotationPresent(EnabledIfSystemProperty.class)
                || AssertionFailureScenarioTest.class.isAnnotationPresent(EnabledIfSystemProperties.class));
        EnabledIfSystemProperty assertion = AssertionFailureScenarioTest.class.getAnnotation(EnabledIfSystemProperty.class);
        EnabledIfSystemProperty exception = ExceptionFailureScenarioTest.class.getAnnotation(EnabledIfSystemProperty.class);
        EnabledIfSystemProperty validation = ValidationFailureScenarioTest.class.getAnnotation(EnabledIfSystemProperty.class);
        EnabledIfSystemProperty http5xx = DependencyHttp5xxScenarioTest.class.getAnnotation(EnabledIfSystemProperty.class);

        assertEquals("fail.scenario", assertion.named());
        assertEquals("assertion", assertion.matches());
        assertEquals("exception", exception.matches());
        assertEquals("validation", validation.matches());
        assertEquals("http5xx", http5xx.matches());
        assertFalse(SUPPORTED.contains("prod-break"));
    }
}
