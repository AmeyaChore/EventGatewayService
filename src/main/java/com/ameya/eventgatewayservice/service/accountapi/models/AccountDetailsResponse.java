package com.ameya.eventgatewayservice.service.accountapi.models;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record AccountDetailsResponse(
        String accountId,
        Instant createdAt,
        BigDecimal balance,
        List<TransactionResponse> recentTransactions
) {}