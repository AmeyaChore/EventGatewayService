package com.ameya.eventgatewayservice.exception;

/**
 * Thrown when the Account Service call fails (timeout, connection refused,
 * circuit breaker open, etc). Caught at the controller boundary and mapped
 * to a 503, never allowed to surface as a generic 500.
 */
public class AccountServiceUnavailableException extends RuntimeException {
    public AccountServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public AccountServiceUnavailableException(String message) {
        super(message);
    }
}