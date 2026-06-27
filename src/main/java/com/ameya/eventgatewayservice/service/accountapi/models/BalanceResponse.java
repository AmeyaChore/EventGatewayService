package com.ameya.eventgatewayservice.service.accountapi.models;

import java.math.BigDecimal;

public record BalanceResponse(String accountId, BigDecimal balance) {}
