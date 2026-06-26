package com.ameya.eventgatewayservice.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

public record EventRequest(

        @NotBlank(message = "eventId is required")
        String eventId,

        @NotBlank(message = "accountId is required")
        String accountId,

        @NotNull(message = "type is required")
        @Pattern(regexp = "CREDIT|DEBIT", message = "type must be CREDIT or DEBIT")
        String type,

        @NotNull(message = "amount is required")
        @DecimalMin(value = "0.0", inclusive = false, message = "amount must be greater than 0")
        BigDecimal amount,

        @NotBlank(message = "currency is required")
        String currency,

        @NotNull(message = "eventTimestamp is required")
        Instant eventTimestamp,

        Map<String, Object> metadata
) {}