package com.tracelab.jenkinsloglab.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;

/**
 * Stand-in for an upstream HTTP dependency (payment/inventory).
 * The default implementation is healthy; tests and opt-in failure
 * scenarios can stub a genuine 5xx response.
 */
@Component
public class DependencyClient {

    public HttpStatus pingPaymentService() {
        return HttpStatus.OK;
    }

    public void requireHealthyPaymentService() {
        HttpStatus status = pingPaymentService();
        if (status.is5xxServerError()) {
            throw HttpServerErrorException.create(
                    status,
                    "payment-service returned " + status.value(),
                    null,
                    ("{\"error\":\"upstream unavailable\",\"status\":" + status.value() + "}").getBytes(),
                    null
            );
        }
    }
}
