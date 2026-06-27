package com.ameya.eventgatewayservice.service.accountapi.models;

import java.math.BigDecimal;
import java.time.Instant;

public record TransactionResponse(
        String eventId,
        String accountId,
        TransactionType type,
        BigDecimal amount,
        String currency,
        Instant transactionTimestamp
) {
    public static TransactionResponse from(TransactionEntity entity) {
        return new TransactionResponse(
                entity.getEventId(),
                entity.getAccountId(),
                entity.getType(),
                entity.getAmount(),
                entity.getCurrency(),
                entity.getTransactionTimestamp()
        );
    }
}