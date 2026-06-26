package com.ameya.eventgatewayservice.service.accountapi.models;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Payload shape for the Account Service's POST /accounts/{accountId}/transactions.
 * Deliberately a separate type from EventRequest: the two services have their
 * own contracts, and coupling them to the same class would leak Gateway
 * internals (like metadata) across the service boundary.
 */
public record TransactionRequest(
        String eventId,
        String type,
        BigDecimal amount,
        String currency,
        Instant eventTimestamp
) {}