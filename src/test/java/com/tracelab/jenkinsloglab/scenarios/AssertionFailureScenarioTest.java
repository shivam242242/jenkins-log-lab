package com.tracelab.jenkinsloglab.scenarios;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Opt-in: {@code mvn test -Dfail.scenario=assertion} or {@code -Pfail-assertion}.
 * Disabled during the default green build.
 */
@EnabledIfSystemProperty(named = "fail.scenario", matches = "assertion")
class AssertionFailureScenarioTest {

    @Test
    void storageClusterIdMustBePresent() {
        String actualClusterId = "";
        assertEquals("storage-cluster-east-1", actualClusterId, "AssertionError: storage cluster id missing");
    }
}
