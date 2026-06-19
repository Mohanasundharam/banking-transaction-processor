package com.banking.repository;

import com.banking.domain.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    // Used by GET /accounts/{id}/transactions — returns newest first.
    List<Transaction> findByAccountIdOrderByTimestampDesc(UUID accountId);
}
