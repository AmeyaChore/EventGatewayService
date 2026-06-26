package com.ameya.eventgatewayservice.model;

import jakarta.persistence.*;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "events", indexes = {
        @Index(name = "idx_account_timestamp", columnList = "accountId, eventTimestamp")
})
public class EventEntity {

    @Id
    private String eventId;

    @Column(nullable = false)
    private String accountId;

    @Column(nullable = false)
    private String type;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency;

    @Column(nullable = false)
    private Instant eventTimestamp;

    @Column(nullable = false)
    private Instant receivedAt;

    @Lob
    private String metadataJson;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EventStatus status;

    // Hash of the original payload, used to detect duplicate-but-different submissions
    @Column(nullable = false)
    private int payloadHash;

    protected EventEntity() {
        // JPA
    }

    public EventEntity(String eventId, String accountId, String type, BigDecimal amount,
                        String currency, Instant eventTimestamp, Instant receivedAt,
                        String metadataJson, EventStatus status, int payloadHash) {
        this.eventId = eventId;
        this.accountId = accountId;
        this.type = type;
        this.amount = amount;
        this.currency = currency;
        this.eventTimestamp = eventTimestamp;
        this.receivedAt = receivedAt;
        this.metadataJson = metadataJson;
        this.status = status;
        this.payloadHash = payloadHash;
    }

    public String getEventId() { return eventId; }
    public String getAccountId() { return accountId; }
    public String getType() { return type; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public Instant getEventTimestamp() { return eventTimestamp; }
    public Instant getReceivedAt() { return receivedAt; }
    public String getMetadataJson() { return metadataJson; }
    public EventStatus getStatus() { return status; }
    public int getPayloadHash() { return payloadHash; }

}