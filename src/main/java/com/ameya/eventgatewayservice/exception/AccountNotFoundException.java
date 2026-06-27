package com.ameya.eventgatewayservice.exception;

/**
 * Thrown when the Account Service responds with 404 (its own
 * AccountNotFoundException), meaning the account genuinely doesn't exist -
 * as opposed to AccountServiceUnavailableException, which means the Account
 * Service couldn't be reached or failed at the infrastructure level (5xx,
 * timeout, connection refused, circuit breaker open).
 *
 * This distinction matters: a 404 here should surface to the Gateway's
 * caller as "this account doesn't exist" (a real, actionable answer), not
 * be masked as "Account Service is unavailable" (which would incorrectly
 * suggest the problem is transient/infrastructural).
 */
public class AccountNotFoundException extends RuntimeException {
    public AccountNotFoundException(String accountId) {
        super("Account '" + accountId + "' was not found");
    }
}