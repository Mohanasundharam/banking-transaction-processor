package com.banking.service;

import com.banking.domain.Account;
import com.banking.domain.Transaction;
import com.banking.domain.TransactionType;
import com.banking.dto.AccountResponse;
import com.banking.dto.DepositRequest;
import com.banking.dto.TransferRequest;
import com.banking.dto.WithdrawRequest;
import com.banking.exception.AccountNotFoundException;
import com.banking.exception.InsufficientFundsException;
import com.banking.exception.SameAccountTransferException;
import com.banking.repository.AccountRepository;
import com.banking.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock  private AccountRepository     accountRepository;
    @Mock  private TransactionRepository transactionRepository;
    @InjectMocks private AccountService  accountService;

    // Primary test account — starts at 1000.00
    private Account sourceAccount;
    private String  sourceId;

    // Secondary account used in transfer tests — starts at 500.00
    private Account destinationAccount;
    private String  destinationId;

    @BeforeEach
    void setUp() {
        sourceAccount      = new Account(new BigDecimal("1000.00"));
        sourceId           = sourceAccount.getId().toString();
        destinationAccount = new Account(new BigDecimal("500.00"));
        destinationId      = destinationAccount.getId().toString();

        // Default stubs shared across all tests in this class. Marked lenient()
        // because not every test exercises every stub (e.g. deposit_invalid_amount
        // never reaches findById/save at all) — Mockito's strict stubbing would
        // otherwise fail those tests with UnnecessaryStubbingException even though
        // the stubs exist correctly for the tests that DO need them.
        lenient().when(accountRepository.findById(sourceAccount.getId()))
                .thenReturn(Optional.of(sourceAccount));
        lenient().when(accountRepository.findById(destinationAccount.getId()))
                .thenReturn(Optional.of(destinationAccount));

        lenient().when(accountRepository.save(any(Account.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(accountRepository.saveAll(anyList()))
                .thenAnswer(inv -> inv.getArgument(0));

        lenient().when(transactionRepository.save(any(Transaction.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    // =========================================================================
    // DEPOSIT
    // =========================================================================

    @Test
    @DisplayName("deposit: balance increases and a DEPOSIT transaction is persisted")
    void deposit_success() {
        // Arrange
        BigDecimal amount  = new BigDecimal("250.00");
        DepositRequest req = new DepositRequest(amount);

        // Act
        AccountResponse response = accountService.deposit(sourceId, req);

        // Assert – returned balance
        assertThat(response.getBalance()).isEqualByComparingTo("1250.00");

        ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(accountCaptor.capture());
        assertThat(accountCaptor.getValue().getBalance()).isEqualByComparingTo("1250.00");

        ArgumentCaptor<Transaction> txCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(txCaptor.capture());
        Transaction tx = txCaptor.getValue();
        assertThat(tx.getType()).isEqualTo(TransactionType.DEPOSIT);
        assertThat(tx.getAmount()).isEqualByComparingTo(amount);
        assertThat(tx.getAccountId()).isEqualTo(sourceAccount.getId());
        assertThat(tx.getReference()).isNull();
        assertThat(tx.getTimestamp()).isNotNull();
    }

    @Test
    @DisplayName("deposit: zero amount is rejected before any persistence")
    void deposit_invalid_amount_zero() {
        assertThatThrownBy(() -> accountService.deposit(sourceId, new DepositRequest(BigDecimal.ZERO)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("greater than zero");
        verify(accountRepository, never()).save(any());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("deposit: negative amount is rejected before any persistence")
    void deposit_invalid_amount_negative() {
        assertThatThrownBy(() -> accountService.deposit(sourceId, new DepositRequest(new BigDecimal("-1.00"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("greater than zero");
        verify(accountRepository, never()).save(any());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("deposit: unknown account ID throws AccountNotFoundException")
    void deposit_account_not_found() {
        when(accountRepository.findById(any(UUID.class))).thenReturn(Optional.empty());
        assertThatThrownBy(() ->
                accountService.deposit(UUID.randomUUID().toString(),
                                       new DepositRequest(new BigDecimal("100.00"))))
                .isInstanceOf(AccountNotFoundException.class);
        verify(transactionRepository, never()).save(any());
    }

    // =========================================================================
    // WITHDRAWAL
    // =========================================================================

    @Test
    @DisplayName("withdraw: balance decreases and a WITHDRAWAL transaction is persisted")
    void withdraw_success() {
        // Arrange
        BigDecimal amount   = new BigDecimal("300.00");
        WithdrawRequest req = new WithdrawRequest(amount);

        // Act
        AccountResponse response = accountService.withdraw(sourceId, req);

        // Assert
        assertThat(response.getBalance()).isEqualByComparingTo("700.00");

        ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(accountCaptor.capture());
        assertThat(accountCaptor.getValue().getBalance()).isEqualByComparingTo("700.00");

        ArgumentCaptor<Transaction> txCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(txCaptor.capture());
        Transaction tx = txCaptor.getValue();
        assertThat(tx.getType()).isEqualTo(TransactionType.WITHDRAWAL);
        assertThat(tx.getAmount()).isEqualByComparingTo(amount);
        assertThat(tx.getAccountId()).isEqualTo(sourceAccount.getId());
        assertThat(tx.getReference()).isNull();
    }

    @Test
    @DisplayName("withdraw: amount exceeding balance throws InsufficientFundsException, nothing persisted")
    void withdraw_insufficient_funds() {
        // Arrange – 1000.01 exceeds the 1000.00 balance
        WithdrawRequest req = new WithdrawRequest(new BigDecimal("1000.01"));

        // Act & Assert
        assertThatThrownBy(() -> accountService.withdraw(sourceId, req))
                .isInstanceOf(InsufficientFundsException.class)
                .hasMessageContaining("1000")
                .hasMessageContaining("1000.01");

        assertThat(sourceAccount.getBalance()).isEqualByComparingTo("1000.00");
        verify(accountRepository, never()).save(any());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("withdraw: exact balance withdrawal succeeds (boundary: balance == amount)")
    void withdraw_exact_balance_succeeds() {
        AccountResponse response = accountService.withdraw(sourceId, new WithdrawRequest(new BigDecimal("1000.00")));
        assertThat(response.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        verify(transactionRepository).save(any(Transaction.class));
    }

    @Test
    @DisplayName("withdraw: zero amount is rejected before any persistence")
    void withdraw_invalid_amount() {
        assertThatThrownBy(() -> accountService.withdraw(sourceId, new WithdrawRequest(BigDecimal.ZERO)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("greater than zero");
        verify(accountRepository, never()).save(any());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("withdraw: negative amount is rejected before any persistence")
    void withdraw_invalid_amount_negative() {
        assertThatThrownBy(() -> accountService.withdraw(sourceId, new WithdrawRequest(new BigDecimal("-50.00"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("greater than zero");
        verify(accountRepository, never()).save(any());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("withdraw: unknown account ID throws AccountNotFoundException")
    void withdraw_account_not_found() {
        when(accountRepository.findById(any(UUID.class))).thenReturn(Optional.empty());
        assertThatThrownBy(() ->
                accountService.withdraw(UUID.randomUUID().toString(),
                                        new WithdrawRequest(new BigDecimal("100.00"))))
                .isInstanceOf(AccountNotFoundException.class);
        verify(transactionRepository, never()).save(any());
    }

    // =========================================================================
    // TRANSFER
    // =========================================================================

    @Test
    @DisplayName("transfer: source debited, destination credited, two linked ledger entries persisted")
    void transfer_success() {
        // Arrange — source: 1000, destination: 500, transfer: 400
        BigDecimal amount   = new BigDecimal("400.00");
        TransferRequest req = new TransferRequest(sourceId, destinationId, amount);

        // Act
        accountService.transfer(req);

        // Assert – balances mutated correctly
        assertThat(sourceAccount.getBalance()).isEqualByComparingTo("600.00");
        assertThat(destinationAccount.getBalance()).isEqualByComparingTo("900.00");

        // Assert – both accounts persisted in one saveAll call
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Account>> saveAllCaptor = ArgumentCaptor.forClass(List.class);
        verify(accountRepository).saveAll(saveAllCaptor.capture());
        List<Account> saved = saveAllCaptor.getValue();
        assertThat(saved).hasSize(2)
                .extracting(Account::getId)
                .containsExactlyInAnyOrder(sourceAccount.getId(), destinationAccount.getId());

        // Assert – exactly two transactions persisted: one TRANSFER_OUT, one TRANSFER_IN
        ArgumentCaptor<Transaction> txCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository, times(2)).save(txCaptor.capture());
        List<Transaction> txns = txCaptor.getAllValues();

        Transaction transferOut = txns.stream()
                .filter(t -> t.getType() == TransactionType.TRANSFER_OUT)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No TRANSFER_OUT found"));
        Transaction transferIn  = txns.stream()
                .filter(t -> t.getType() == TransactionType.TRANSFER_IN)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No TRANSFER_IN found"));

        // Amounts match the requested value
        assertThat(transferOut.getAmount()).isEqualByComparingTo(amount);
        assertThat(transferIn.getAmount()).isEqualByComparingTo(amount);

        // Accounts are correctly assigned
        assertThat(transferOut.getAccountId()).isEqualTo(sourceAccount.getId());
        assertThat(transferIn.getAccountId()).isEqualTo(destinationAccount.getId());

        // Both legs share the same non-null reference (linked transfer pair)
        assertThat(transferOut.getReference()).isNotNull();
        assertThat(transferIn.getReference()).isEqualTo(transferOut.getReference());
    }

    @Test
    @DisplayName("transfer: source balance < amount throws InsufficientFundsException, nothing persisted")
    void transfer_insufficient_balance() {
        // Arrange — source has 1000, attempt to transfer 1000.01
        BigDecimal overAmount = new BigDecimal("1000.01");
        TransferRequest req   = new TransferRequest(sourceId, destinationId, overAmount);

        // Act & Assert
        assertThatThrownBy(() -> accountService.transfer(req))
                .isInstanceOf(InsufficientFundsException.class)
                .hasMessageContaining("1000")
                .hasMessageContaining("1000.01");

        // Assert – neither account was mutated
        assertThat(sourceAccount.getBalance()).isEqualByComparingTo("1000.00");
        assertThat(destinationAccount.getBalance()).isEqualByComparingTo("500.00");

        // Assert – nothing written to the DB
        verify(accountRepository, never()).saveAll(anyList());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("transfer: same source and destination throws SameAccountTransferException")
    void transfer_same_account() {
        // Arrange — fromAccountId == toAccountId
        TransferRequest req = new TransferRequest(sourceId, sourceId, new BigDecimal("100.00"));

        // Act & Assert
        assertThatThrownBy(() -> accountService.transfer(req))
                .isInstanceOf(SameAccountTransferException.class)
                .hasMessageContaining(sourceId);

        // Assert – no DB access at all: same-account guard fires before any lookup
        verify(accountRepository, never()).findById(any(UUID.class));
        verify(accountRepository, never()).saveAll(anyList());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("transfer: unknown source account throws AccountNotFoundException")
    void transfer_source_not_found() {
        when(accountRepository.findById(sourceAccount.getId())).thenReturn(Optional.empty());
        TransferRequest req = new TransferRequest(sourceId, destinationId, new BigDecimal("100.00"));

        assertThatThrownBy(() -> accountService.transfer(req))
                .isInstanceOf(AccountNotFoundException.class);

        verify(accountRepository, never()).saveAll(anyList());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("transfer: unknown destination account throws AccountNotFoundException")
    void transfer_destination_not_found() {
        when(accountRepository.findById(destinationAccount.getId())).thenReturn(Optional.empty());
        TransferRequest req = new TransferRequest(sourceId, destinationId, new BigDecimal("100.00"));

        assertThatThrownBy(() -> accountService.transfer(req))
                .isInstanceOf(AccountNotFoundException.class);

        verify(accountRepository, never()).saveAll(anyList());
        verify(transactionRepository, never()).save(any());
    }
}
