package com.ameya.eventgatewayservice.service.accountapi;

import com.ameya.eventgatewayservice.dto.EventRequest;
import com.ameya.eventgatewayservice.exception.AccountServiceUnavailableException;
import com.ameya.eventgatewayservice.service.accountapi.feign.AccountServiceFeignClient;
import com.ameya.eventgatewayservice.service.accountapi.models.TransactionRequest;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Adapter from the Gateway's internal contract (AccountServiceClient) onto
 * the Feign-generated proxy.
 *
 * The @CircuitBreaker annotation (Resilience4j's own, not Spring Cloud's) is
 * the annotation-based wiring: it wraps applyTransaction() in a circuit
 * breaker named "accountService" without any manual Decorators/factory code.
 * All thresholds for that named instance (sliding window, failure rate,
 * wait duration, etc.) live in application.yml under
 * resilience4j.circuitbreaker.instances.accountService - nothing is
 * hardcoded here.
 *
 * fallbackMethod is required by the annotation: Resilience4j calls it with
 * the same arguments plus the triggering Throwable whenever the breaker is
 * OPEN or the underlying call throws.
 */
@Component
public class AccountServiceClientImpl implements AccountServiceClient {

    private static final Logger log = LoggerFactory.getLogger(AccountServiceClientImpl.class);

    private final AccountServiceFeignClient feignClient;

    public AccountServiceClientImpl(AccountServiceFeignClient feignClient) {
        this.feignClient = feignClient;
    }

    @Override
    @Retry(name = "accountService", fallbackMethod = "applyTransactionFallback")
    @CircuitBreaker(name = "accountService", fallbackMethod = "applyTransactionFallback")
    public void applyTransaction(EventRequest request) {
        TransactionRequest payload = new TransactionRequest(
                request.eventId(),
                request.type(),
                request.amount(),
                request.currency(),
                request.eventTimestamp()
        );
        // eventId doubles as the idempotency key on the Account Service side too -
        // if the Gateway's circuit breaker retries via half-open state, or this
        // call is re-triggered for any reason, the Account Service can recognize
        // the same eventId and avoid applying the transaction twice.
        feignClient.applyTransaction(request.accountId(), request.eventId(), payload);
    }

    /**
     * Invoked by Resilience4j when the breaker is OPEN or the call above
     * throws. Normalizes every failure mode into
     * AccountServiceUnavailableException so EventService and
     * GlobalExceptionHandler never need to know Feign or Resilience4j exist.
     */
    private void applyTransactionFallback(EventRequest request, Throwable cause) {
        log.error("Account Service call failed/short-circuited for eventId={} accountId={}: {}",
                request.eventId(), request.accountId(), cause.getMessage());
        throw new AccountServiceUnavailableException(
                "Account Service is unavailable: " + cause.getMessage(), cause);
    }
}