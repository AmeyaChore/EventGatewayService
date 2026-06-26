package com.ameya.eventgatewayservice.service.accountapi;

import com.ameya.eventgatewayservice.dto.EventRequest;

/**
 * Abstraction over the call to the Account Service. The real implementation
 * wraps a RestClient call with a Resilience4j CircuitBreaker/TimeLimiter and
 * propagates the trace context via HTTP headers. Any failure (timeout,
 * connection refused, breaker open) must surface as
 * AccountServiceUnavailableException - never let a raw RestClientException
 * leak up to the controller.
 */
public interface AccountServiceClient {
    void applyTransaction(EventRequest request);
}