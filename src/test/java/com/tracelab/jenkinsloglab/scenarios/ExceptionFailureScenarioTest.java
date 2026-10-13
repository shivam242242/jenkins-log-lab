package com.tracelab.jenkinsloglab.scenarios;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Opt-in: {@code mvn test -Dfail.scenario=exception} or {@code -Pfail-exception}.
 * Throws a real Java exception so Surefire records a stack trace.
 */
@EnabledIfSystemProperty(named = "fail.scenario", matches = "exception")
class ExceptionFailureScenarioTest {

    @Test
    void orderFulfillmentThrowsRuntimeException() {
        try {
            fulfillOrder("ORD-4421");
        } catch (IllegalStateException ex) {
            throw new IllegalStateException("Failed to fulfill order ORD-4421", ex);
        }
    }

    private void fulfillOrder(String orderId) {
        throw new IllegalStateException("NullPointerException stand-in: paymentIntent is null for " + orderId);
    }
}
