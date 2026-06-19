package com.banking.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "transactions", indexes = {
    @Index(name = "idx_transaction_account_id", columnList = "account_id"),
    @Index(name = "idx_transaction_timestamp",  columnList = "timestamp")
})
public class Transaction {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    // Stored as a plain UUID column — no FK constraint to keep ledger
    // append-only and independent of account lifecycle.
    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, updatable = false, length = 20)
    private TransactionType type;

    // Always stored as a positive value; sign is implied by TransactionType.
    @Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "timestamp", nullable = false, updatable = false)
    private LocalDateTime timestamp;

    // Links the two legs of a TRANSFER (TRANSFER_OUT + TRANSFER_IN share the same reference UUID).
    // Null for DEPOSIT and WITHDRAWAL.
    @Column(name = "reference", updatable = false, length = 36)
    private String reference;

    protected Transaction() {}

    private Transaction(UUID accountId, TransactionType type, BigDecimal amount, String reference) {
        this.id        = UUID.randomUUID();
        this.accountId = accountId;
        this.type      = type;
        this.amount    = amount;
        this.timestamp = LocalDateTime.now();
        this.reference = reference;
    }

    // --- Static factories ---------------------------------------------------

    public static Transaction deposit(UUID accountId, BigDecimal amount) {
        return new Transaction(accountId, TransactionType.DEPOSIT, amount, null);
    }

    public static Transaction withdrawal(UUID accountId, BigDecimal amount) {
        return new Transaction(accountId, TransactionType.WITHDRAWAL, amount, null);
    }

    public static Transaction transferOut(UUID accountId, BigDecimal amount, String reference) {
        return new Transaction(accountId, TransactionType.TRANSFER_OUT, amount, reference);
    }

    public static Transaction transferIn(UUID accountId, BigDecimal amount, String reference) {
        return new Transaction(accountId, TransactionType.TRANSFER_IN, amount, reference);
    }

    // --- Getters ------------------------------------------------------------

    public UUID getId()               { return id; }
    public UUID getAccountId()        { return accountId; }
    public TransactionType getType()  { return type; }
    public BigDecimal getAmount()     { return amount; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public String getReference()      { return reference; }
}
