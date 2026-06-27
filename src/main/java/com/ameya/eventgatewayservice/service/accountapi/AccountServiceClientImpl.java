package com.ameya.eventgatewayservice.service.accountapi;

import com.ameya.eventgatewayservice.dto.EventRequest;
import com.ameya.eventgatewayservice.exception.AccountNotFoundException;
import com.ameya.eventgatewayservice.exception.AccountServiceBadRequestException;
import com.ameya.eventgatewayservice.exception.AccountServiceUnavailableException;
import com.ameya.eventgatewayservice.model.ErrorResponse;
import com.ameya.eventgatewayservice.service.accountapi.feign.AccountServiceFeignClient;
import com.ameya.eventgatewayservice.service.accountapi.models.AccountDetailsResponse;
import com.ameya.eventgatewayservice.service.accountapi.models.BalanceResponse;
import com.ameya.eventgatewayservice.service.accountapi.models.TransactionRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
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

    private final ObjectMapper objectMapper = new ObjectMapper();

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

        ResponseEntity<Object> response = feignClient.applyTransaction(request.accountId(), request.eventId(), payload);
    }

    @Override
    @Retry(name = "accountServiceRead", fallbackMethod = "getBalanceFallback")
    @CircuitBreaker(name = "accountServiceRead", fallbackMethod = "getBalanceFallback")
    public BalanceResponse getBalance(String accountId) {
        return feignClient.getBalance(accountId).getBody();
    }

    @Override
    @Retry(name = "accountServiceRead", fallbackMethod = "getAccountFallback")
    @CircuitBreaker(name = "accountServiceRead", fallbackMethod = "getAccountFallback")
    public AccountDetailsResponse getAccount(String accountId) {
        return feignClient.getAccount(accountId).getBody();
    }



    /**
     * Invoked by Resilience4j when the breaker is OPEN or the call above
     * throws. Normalizes every failure mode into
     * AccountServiceUnavailableException so EventService and
     * GlobalExceptionHandler never need to know Feign or Resilience4j exist.
     */
    /**
     * Invoked by Resilience4j only when applyTransaction() above actually
     * threw (i.e. a non-2xx status, or retries/breaker exhausted - 2xx
     * never lands here at all).
     */
    private void applyTransactionFallback(EventRequest request, Throwable cause) {
        if (!(cause instanceof FeignException feignEx)) {
            // Not even a Feign-level failure (e.g. CallNotPermittedException
            // from the breaker being OPEN) - no status code to check.
            log.error("Account Service call failed/short-circuited for eventId={} accountId={}: {}",
                    request.eventId(), request.accountId(), cause.getMessage());
            throw new AccountServiceUnavailableException(
                    "Account Service is unavailable: " + cause.getMessage(), cause);
        }

        ErrorResponse errorBody = parseErrorBody(feignEx);
        String message = errorBody != null ? errorBody.message() : feignEx.getMessage();

        if (feignEx.status() == 404) {
            log.warn("Account Service reported account not found for accountId={} eventId={}: {}",
                    request.accountId(), request.eventId(), message);
            throw new AccountNotFoundException(request.accountId());
        }

        if (feignEx.status() == 400) {
            log.error("Account Service rejected request as invalid for accountId={} eventId={}: {}",
                    request.accountId(), request.eventId(), message);
            throw new AccountServiceBadRequestException(message);
        }

        log.error("Account Service call failed for eventId={} accountId={} status={}: {}",
                request.eventId(), request.accountId(), feignEx.status(), message);
        throw new AccountServiceUnavailableException(
                "Account Service is unavailable: " + message, feignEx);
    }

    /**
     * Invoked by Resilience4j only when getBalance() above actually threw.
     * Same status-branching shape as applyTransactionFallback, but with no
     * eventId in scope (balance lookups aren't tied to a specific event).
     */
    /** Invoked by Resilience4j only when getBalance() above actually threw. */
    private BalanceResponse getBalanceFallback(String accountId, Throwable cause) {
        translateAccountIdFailure(accountId, cause, "balance lookup");
        return null; // unreachable - translateAccountIdFailure always throws
    }

    /** Invoked by Resilience4j only when getAccount() above actually threw. */
    private AccountDetailsResponse getAccountFallback(String accountId, Throwable cause) {
        translateAccountIdFailure(accountId, cause, "account details lookup");
        return null; // unreachable - translateAccountIdFailure always throws
    }

    /**
     * Shared status-branching logic for the read-only methods (no eventId
     * in scope, unlike applyTransactionFallback). ALWAYS throws - never
     * returns normally - callers exist only to satisfy the compiler's
     * return-type requirement on their own fallback methods.
     */
    private void translateAccountIdFailure(String accountId, Throwable cause, String operationName) {
        if (!(cause instanceof FeignException feignEx)) {
            log.error("Account Service {} failed/short-circuited for accountId={}: {}",
                    operationName, accountId, cause.getMessage());
            throw new AccountServiceUnavailableException(
                    "Account Service is unavailable: " + cause.getMessage(), cause);
        }

        ErrorResponse errorBody = parseErrorBody(feignEx);
        String message = errorBody != null ? errorBody.message() : feignEx.getMessage();

        if (feignEx.status() == 404) {
            log.warn("Account Service reported account not found during {} for accountId={}: {}",
                    operationName, accountId, message);
            throw new AccountNotFoundException(accountId);
        }

        if (feignEx.status() == 400) {
            log.error("Account Service rejected {} as invalid for accountId={}: {}",
                    operationName, accountId, message);
            throw new AccountServiceBadRequestException(message);
        }

        log.error("Account Service {} failed for accountId={} status={}: {}",
                operationName, accountId, feignEx.status(), message);
        throw new AccountServiceUnavailableException(
                "Account Service is unavailable: " + message, feignEx);
    }

    /** Parses the Account Service's JSON error body. Returns null if missing/unparseable. */
    private ErrorResponse parseErrorBody(FeignException feignEx) {
        String body = feignEx.contentUTF8();
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(body, ErrorResponse.class);
        } catch (Exception e) {
            log.warn("Could not parse Account Service error body as JSON: {}", body);
            return null;
        }
    }
}