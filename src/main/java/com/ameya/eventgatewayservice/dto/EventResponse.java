package com.ameya.eventgatewayservice.dto;

import com.ameya.eventgatewayservice.model.EventEntity;
import com.ameya.eventgatewayservice.model.EventStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

public record EventResponse(
        String eventId,
        String accountId,
        String type,
        BigDecimal amount,
        String currency,
        Instant eventTimestamp,
        Map<String, Object> metadata,
        EventStatus status
) {
    public static EventResponse from(EventEntity entity, Map<String, Object> metadata) {
        return new EventResponse(
                entity.getEventId(),
                entity.getAccountId(),
                entity.getType(),
                entity.getAmount(),
                entity.getCurrency(),
                entity.getEventTimestamp(),
                metadata,
                entity.getStatus()
        );
    }
}