package com.banking.dto;

import com.banking.domain.Transaction;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

public class TransactionHistoryResponse {

    private final String        type;
    private final BigDecimal    amount;
    private final LocalDateTime timestamp;
    private final String        reference; // null for DEPOSIT / WITHDRAWAL

    private TransactionHistoryResponse(String type,
                                       BigDecimal amount,
                                       LocalDateTime timestamp,
                                       String reference) {
        this.type      = type;
        this.amount    = amount;
        this.timestamp = timestamp;
        this.reference = reference;
    }

    /**
     * Maps a single {@link Transaction} entity to a response DTO.
     * The JPA entity never leaves the service layer.
     */
    public static TransactionHistoryResponse from(Transaction tx) {
        return new TransactionHistoryResponse(
                tx.getType().name(),   // enum → String; avoids exposing the enum type
                tx.getAmount(),
                tx.getTimestamp(),
                tx.getReference()      // null for DEPOSIT / WITHDRAWAL — serialised as JSON null
        );
    }

    /**
     * Bulk mapping helper — maps an ordered list of transactions in one call.
     * Preserves the order returned by the repository (timestamp DESC).
     */
    public static List<TransactionHistoryResponse> fromList(List<Transaction> transactions) {
        return transactions.stream()
                .map(TransactionHistoryResponse::from)
                .collect(Collectors.toList());
    }

    public String        getType()      { return type; }
    public BigDecimal    getAmount()    { return amount; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public String        getReference() { return reference; }
}
