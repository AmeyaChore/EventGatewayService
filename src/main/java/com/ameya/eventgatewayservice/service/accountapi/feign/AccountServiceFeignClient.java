package com.ameya.eventgatewayservice.service.accountapi.feign;

import com.ameya.eventgatewayservice.service.accountapi.models.TransactionRequest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

/**
 * Declarative HTTP client for the Account Service.
 * Base URL is externalized to application.yml (accountservice.url) so it can
 * be swapped per-environment (local, docker-compose, tests).
 *
 * No fallbackFactory/circuitbreaker config here: the breaker is applied one
 * layer up, via @CircuitBreaker on AccountServiceClientImpl.applyTransaction().
 * Keeping the breaker off the Feign client itself avoids two independent
 * breaker layers (Feign's circuitbreaker bridge AND Resilience4j's
 * annotation) double-counting the same failures.
 *
 * Two header concerns are handled differently here:
 *  - Trace ID: same value needed on every call regardless of which method is
 *    invoked, so it's injected globally via FeignClientConfig's
 *    RequestInterceptor bean.
 *  - Idempotency-Key: specific to THIS call's eventId, so it's passed as an
 *    explicit @RequestHeader parameter instead - simpler and more visible
 *    than threading per-call state through a shared interceptor.
 */
@FeignClient(
        name = "account-service",
        url = "${accountService.url}",
        configuration = FeignClientConfig.class
)
public interface AccountServiceFeignClient {

    @PostMapping("/accounts/{accountId}/transactions")
    ResponseEntity<Void> applyTransaction(
            @PathVariable String accountId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody TransactionRequest request
    );
}