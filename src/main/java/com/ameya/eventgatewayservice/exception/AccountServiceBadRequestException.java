package com.ameya.eventgatewayservice.exception;

public class AccountServiceBadRequestException extends RuntimeException {
    public AccountServiceBadRequestException(String message) {
        super(message);
    }
}