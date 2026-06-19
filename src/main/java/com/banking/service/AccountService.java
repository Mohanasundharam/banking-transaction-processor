package com.banking.service;

import com.banking.domain.Account;
import com.banking.domain.Transaction;
import com.banking.dto.AccountResponse;
import com.banking.dto.BalanceResponse;
import com.banking.dto.CreateAccountRequest;
import com.banking.dto.DepositRequest;
import com.banking.dto.TransactionHistoryResponse;
import com.banking.dto.TransferRequest;
import com.banking.dto.WithdrawRequest;
import com.banking.exception.AccountNotFoundException;
import com.banking.exception.InsufficientFundsException;
import com.banking.exception.SameAccountTransferException;
import com.banking.repository.AccountRepository;
import com.banking.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
public class AccountService {

    private final AccountRepository     accountRepository;
    private final TransactionRepository transactionRepository;

    public AccountService(AccountRepository accountRepository,
                          TransactionRepository transactionRepository) {
        this.accountRepository     = accountRepository;
        this.transactionRepository = transactionRepository;
    }

    // -------------------------------------------------------------------------
    // Create account
    // -------------------------------------------------------------------------

    @Transactional
    public AccountResponse createAccount(CreateAccountRequest request) {
        Account account = new Account(request.getInitialBalance());
        accountRepository.save(account);
        return AccountResponse.from(account);
    }

    // -------------------------------------------------------------------------
    // Deposit
    // -------------------------------------------------------------------------

    @Transactional
    public AccountResponse deposit(String accountId, DepositRequest request) {
        BigDecimal amount = request.getAmount();

        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(
                    "Deposit amount must be greater than zero, got: " + amount);
        }

        Account account = findAccountById(accountId);
        account.setBalance(account.getBalance().add(amount));
        accountRepository.save(account);
        transactionRepository.save(Transaction.deposit(account.getId(), amount));

        return AccountResponse.from(account);
    }

    // -------------------------------------------------------------------------
    // Withdraw
    // -------------------------------------------------------------------------

    @Transactional
    public AccountResponse withdraw(String accountId, WithdrawRequest request) {
        BigDecimal amount = request.getAmount();

        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(
                    "Withdrawal amount must be greater than zero, got: " + amount);
        }

        Account account = findAccountById(accountId);

        if (account.getBalance().compareTo(amount) < 0) {
            throw new InsufficientFundsException(
                    String.format("Insufficient funds: balance is %s but withdrawal requested %s",
                            account.getBalance().toPlainString(),
                            amount.toPlainString()));
        }

        account.setBalance(account.getBalance().subtract(amount));
        accountRepository.save(account);
        transactionRepository.save(Transaction.withdrawal(account.getId(), amount));

        return AccountResponse.from(account);
    }

    // -------------------------------------------------------------------------
    // Transfer
    // -------------------------------------------------------------------------

    @Transactional
    public void transfer(TransferRequest request) {
        BigDecimal amount = request.getAmount();

        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(
                    "Transfer amount must be greater than zero, got: " + amount);
        }

        if (request.getFromAccountId().equals(request.getToAccountId())) {
            throw new SameAccountTransferException(request.getFromAccountId());
        }

        Account source      = findAccountById(request.getFromAccountId());
        Account destination = findAccountById(request.getToAccountId());

        if (source.getBalance().compareTo(amount) < 0) {
            throw new InsufficientFundsException(
                    String.format("Insufficient funds for transfer: source balance is %s but %s requested",
                            source.getBalance().toPlainString(),
                            amount.toPlainString()));
        }

        source.setBalance(source.getBalance().subtract(amount));
        destination.setBalance(destination.getBalance().add(amount));
        accountRepository.saveAll(List.of(source, destination));

        String transferReference = UUID.randomUUID().toString();
        transactionRepository.save(
                Transaction.transferOut(source.getId(), amount, transferReference));
        transactionRepository.save(
                Transaction.transferIn(destination.getId(), amount, transferReference));
    }

    // -------------------------------------------------------------------------
    // Get balance
    // -------------------------------------------------------------------------

    // Read-only — no dirty writes; @Transactional(readOnly=true) lets Hibernate
    // skip flush and the DB driver can route to a read replica if one exists.
    @Transactional(readOnly = true)
    public BalanceResponse getBalance(String accountId) {
        Account account = findAccountById(accountId);
        return BalanceResponse.from(account);
    }

    // -------------------------------------------------------------------------
    // Get transaction history
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<TransactionHistoryResponse> getTransactionHistory(String accountId) {
        // Verify the account exists — return 404 for unknown accounts even if
        // the transaction table happens to have orphaned records for that ID.
        Account account = findAccountById(accountId);

        List<Transaction> transactions =
                transactionRepository.findByAccountIdOrderByTimestampDesc(account.getId());

        // Returns an empty list (not a 404) when the account has no transactions.
        return TransactionHistoryResponse.fromList(transactions);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    Account findAccountById(String accountId) {
        UUID uuid;
        try {
            uuid = UUID.fromString(accountId);
        } catch (IllegalArgumentException e) {
            throw new AccountNotFoundException(accountId);
        }
        return accountRepository.findById(uuid)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
    }
}
