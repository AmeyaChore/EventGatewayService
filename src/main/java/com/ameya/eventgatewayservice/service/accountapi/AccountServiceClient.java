package com.ameya.eventgatewayservice.service.accountapi;

import com.ameya.eventgatewayservice.dto.EventRequest;
import com.ameya.eventgatewayservice.service.accountapi.models.AccountDetailsResponse;
import com.ameya.eventgatewayservice.service.accountapi.models.BalanceResponse;

import java.math.BigDecimal;

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
    /**
     * @return the account's current balance, computed fresh by the Account
     *         Service (sum of CREDITs minus sum of DEBITs).
     * @throws com.ameya.eventgatewayservice.exception.AccountNotFoundException
     *         if the Account Service reports the account doesn't exist (404)
     */
    BalanceResponse getBalance(String accountId);

    /**
     * @return account details (createdAt, balance, recent transactions)
     *         as reported by the Account Service.
     * @throws com.ameya.eventgatewayservice.exception.AccountNotFoundException
     *         if the Account Service reports the account doesn't exist (404)
     */
    AccountDetailsResponse getAccount(String accountId);
}