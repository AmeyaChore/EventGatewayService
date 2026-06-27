package com.ameya.eventgatewayservice.service.accountapi.models;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * A single CREDIT or DEBIT ledger entry for an account.
 *
 * Balance is NEVER stored as a mutable running total anywhere in this
 * service - it's always computed by summing Transaction rows on demand
 * (see AccountService/AccountRepository). This is what makes balance
 * correctness independent of arrival order: addition is commutative, so it
 * doesn't matter whether a transaction with an earlier eventTimestamp is
 * inserted before or after one with a later eventTimestamp - the sum is
 * identical either way. A running-total column updated incrementally on
 * each write would NOT have this property and is deliberately avoided.
 */
@Entity
@Table(name = "transactions", indexes = {
        @Index(name = "idx_account_id_timestamp", columnList = "accountId, transactionTimestamp")
})
public class TransactionEntity {

    /**
     * The eventId from the Gateway, received as the Idempotency-Key header.
     * Used as the primary key specifically so a duplicate submission (same
     * eventId) is rejected by the database's own uniqueness constraint,
     * giving idempotency for free at the persistence layer rather than
     * needing a separate existence-check-then-insert race.
     */
    @Id
    private String eventId;

    @Column(nullable = false)
    private String accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionType type;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency;

    /**
     * When the transaction actually occurred, per the original event.
     * Deliberately NOT used to gate whether a transaction is accepted
     * (no "reject if older than the last one we saw" logic) - out-of-order
     * arrival must still be accepted and summed correctly. This field
     * exists for chronological listing/auditing, not for balance logic.
     */
    @Column(nullable = false)
    private Instant transactionTimestamp;

    /** When the Account Service itself recorded this row - for auditing/debugging arrival order. */
    @Column(nullable = false)
    private Instant receivedAt;

    protected TransactionEntity() {
        // JPA
    }

    public TransactionEntity(String eventId, String accountId, TransactionType type,
                             BigDecimal amount, String currency,
                             Instant transactionTimestamp, Instant receivedAt) {
        this.eventId = eventId;
        this.accountId = accountId;
        this.type = type;
        this.amount = amount;
        this.currency = currency;
        this.transactionTimestamp = transactionTimestamp;
        this.receivedAt = receivedAt;
    }

    public String getEventId() { return eventId; }
    public String getAccountId() { return accountId; }
    public TransactionType getType() { return type; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public Instant getTransactionTimestamp() { return transactionTimestamp; }
    public Instant getReceivedAt() { return receivedAt; }
}