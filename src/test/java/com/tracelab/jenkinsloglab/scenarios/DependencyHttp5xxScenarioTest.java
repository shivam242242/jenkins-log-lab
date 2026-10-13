package com.tracelab.jenkinsloglab.scenarios;

import com.tracelab.jenkinsloglab.service.DependencyClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Opt-in: {@code mvn test -Dfail.scenario=http5xx} or {@code -Pfail-http5xx}.
 * Simulates an upstream HTTP 503 and fails the test with that exception in the Surefire log.
 */
@EnabledIfSystemProperty(named = "fail.scenario", matches = "http5xx")
class DependencyHttp5xxScenarioTest {

    @Test
    void paymentServiceMustReturn2xx() {
        DependencyClient client = new DependencyClient() {
            @Override
            public HttpStatus pingPaymentService() {
                return HttpStatus.SERVICE_UNAVAILABLE;
            }
        };

        try {
            client.requireHealthyPaymentService();
            fail("Expected HTTP 5xx from payment-service");
        } catch (HttpServerErrorException ex) {
            assertEquals(HttpStatus.OK, ex.getStatusCode(),
                    "payment-service returned HTTP " + ex.getStatusCode().value() + " with body " + ex.getResponseBodyAsString());
        }
    }
}
