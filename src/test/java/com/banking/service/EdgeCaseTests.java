package com.banking.service;

import com.banking.domain.Account;
import com.banking.domain.Transaction;
import com.banking.dto.DepositRequest;
import com.banking.dto.TransferRequest;
import com.banking.dto.WithdrawRequest;
import com.banking.exception.AccountNotFoundException;
import com.banking.exception.InsufficientFundsException;
import com.banking.exception.SameAccountTransferException;
import com.banking.repository.AccountRepository;
import com.banking.repository.TransactionRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * Edge-case unit tests for the service layer.
 *
 * Each nested class groups cases by operation, making it easy to run a
 * single category in isolation:  mvn test -Dtest="EdgeCaseTests$Deposit"
 *
 * Bean-Validation (ConstraintViolation) cases use the Jakarta Validator
 * directly — no Spring context needed — so they run as pure unit tests.
 */
@ExtendWith(MockitoExtension.class)
class EdgeCaseTests {

    // ── Mocks & SUT ──────────────────────────────────────────────────────────
    @Mock  private AccountRepository     accountRepository;
    @Mock  private TransactionRepository transactionRepository;
    @InjectMocks private AccountService  accountService;

    // ── Shared fixtures ───────────────────────────────────────────────────────
    private Account account;
    private String  accountId;
    private Account otherAccount;
    private String  otherAccountId;

    /** Jakarta Validator used to exercise @Positive / @NotNull without Spring */
    private Validator validator;

    @BeforeEach
    void setUp() {
        account        = new Account(new BigDecimal("500.00"));
        accountId      = account.getId().toString();
        otherAccount   = new Account(new BigDecimal("200.00"));
        otherAccountId = otherAccount.getId().toString();

        // lenient(): most edge-case tests intentionally short-circuit before
        // reaching the repository (invalid amount, same-account, bean-validation
        // cases), so they never touch some or all of these stubs. Without
        // lenient(), Mockito's strict stubbing fails those tests with
        // UnnecessaryStubbingException even though nothing is actually wrong.
        lenient().when(accountRepository.findById(account.getId()))
                .thenReturn(Optional.of(account));
        lenient().when(accountRepository.findById(otherAccount.getId()))
                .thenReturn(Optional.of(otherAccount));
        lenient().when(accountRepository.save(any(Account.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(accountRepository.saveAll(anyList()))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(transactionRepository.save(any(Transaction.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    // =========================================================================
    // DEPOSIT edge cases
    // =========================================================================

    @Nested
    @DisplayName("Deposit edge cases")
    class Deposit {

        @Test
        @DisplayName("negative amount is rejected by service guard before any DB call")
        void negative_deposit_amount_throws_exception() {
            // Arrange
            DepositRequest req = new DepositRequest(new BigDecimal("-0.01"));

            // Act & Assert
            assertThatThrownBy(() -> accountService.deposit(accountId, req))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("greater than zero");

            // Nothing should reach the DB
            verify(accountRepository, never()).save(any());
            verify(transactionRepository, never()).save(any());
        }

        @Test
        @DisplayName("null amount on DepositRequest fails Bean Validation with ConstraintViolation")
        void null_amount_fails_bean_validation() {
            // Arrange — simulate what @Valid would enforce at the controller boundary
            DepositRequest req = new DepositRequest(null);

            // Act
            Set<ConstraintViolation<DepositRequest>> violations = validator.validate(req);

            // Assert — at least one violation on 'amount'
            assertThat(violations).isNotEmpty();
            assertThat(violations)
                    .extracting(cv -> cv.getPropertyPath().toString())
                    .contains("amount");
            assertThat(violations)
                    .extracting(ConstraintViolation::getMessage)
                    .anyMatch(msg -> msg.toLowerCase().contains("null")
                               || msg.toLowerCase().contains("not null"));
        }

        @Test
        @DisplayName("deposit to a non-existent account throws AccountNotFoundException")
        void deposit_to_non_existent_account_throws_AccountNotFoundException() {
            // Arrange — override default stub: this UUID is unknown
            String unknownId = UUID.randomUUID().toString();
            when(accountRepository.findById(any(UUID.class))).thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() ->
                    accountService.deposit(unknownId, new DepositRequest(new BigDecimal("100.00"))))
                    .isInstanceOf(AccountNotFoundException.class)
                    .hasMessageContaining(unknownId);

            verify(transactionRepository, never()).save(any());
        }

        @Test
        @DisplayName("malformed (non-UUID) account ID throws AccountNotFoundException")
        void malformed_account_id_throws_AccountNotFoundException() {
            assertThatThrownBy(() ->
                    accountService.deposit("not-a-uuid", new DepositRequest(new BigDecimal("50.00"))))
                    .isInstanceOf(AccountNotFoundException.class)
                    .hasMessageContaining("not-a-uuid");

            verifyNoInteractions(transactionRepository);
        }
    }

    // =========================================================================
    // WITHDRAWAL edge cases
    // =========================================================================

    @Nested
    @DisplayName("Withdrawal edge cases")
    class Withdrawal {

        @Test
        @DisplayName("zero amount is rejected by service guard before any DB call")
        void zero_withdrawal_amount_throws_exception() {
            // Arrange
            WithdrawRequest req = new WithdrawRequest(BigDecimal.ZERO);

            // Act & Assert
            assertThatThrownBy(() -> accountService.withdraw(accountId, req))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("greater than zero");

            verify(accountRepository, never()).save(any());
            verify(transactionRepository, never()).save(any());
        }

        @Test
        @DisplayName("overdraft attempt throws InsufficientFundsException — balance unchanged, nothing persisted")
        void overdraft_attempt_throws_InsufficientFundsException() {
            // Arrange — account has 500.00, attempt to withdraw 500.01
            BigDecimal overdraftAmount = new BigDecimal("500.01");
            WithdrawRequest req        = new WithdrawRequest(overdraftAmount);

            // Act & Assert
            assertThatThrownBy(() -> accountService.withdraw(accountId, req))
                    .isInstanceOf(InsufficientFundsException.class)
                    .hasMessageContaining("500")      // current balance in message
                    .hasMessageContaining("500.01");  // requested amount in message

            // Balance must be completely unchanged
            assertThat(account.getBalance()).isEqualByComparingTo("500.00");

            // Nothing written to DB
            verify(accountRepository, never()).save(any());
            verify(transactionRepository, never()).save(any());
        }

        @Test
        @DisplayName("null amount on WithdrawRequest fails Bean Validation with ConstraintViolation")
        void null_amount_fails_bean_validation() {
            WithdrawRequest req = new WithdrawRequest(null);

            Set<ConstraintViolation<WithdrawRequest>> violations = validator.validate(req);

            assertThat(violations).isNotEmpty();
            assertThat(violations)
                    .extracting(cv -> cv.getPropertyPath().toString())
                    .contains("amount");
        }

        @Test
        @DisplayName("withdrawal of exactly the full balance succeeds — boundary condition")
        void exact_full_balance_withdrawal_succeeds() {
            // Arrange
            WithdrawRequest req = new WithdrawRequest(new BigDecimal("500.00"));

            // Act — should not throw
            accountService.withdraw(accountId, req);

            // Assert — balance drains to zero, not negative
            assertThat(account.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
            verify(transactionRepository).save(any(Transaction.class));
        }
    }

    // =========================================================================
    // TRANSFER edge cases
    // =========================================================================

    @Nested
    @DisplayName("Transfer edge cases")
    class Transfer {

        @Test
        @DisplayName("transfer to same account throws SameAccountTransferException — no DB access")
        void transfer_to_same_account_throws_SameAccountTransferException() {
            // Arrange — deliberately use the same ID for both ends
            TransferRequest req = new TransferRequest(accountId, accountId, new BigDecimal("100.00"));

            // Act & Assert
            assertThatThrownBy(() -> accountService.transfer(req))
                    .isInstanceOf(SameAccountTransferException.class)
                    .hasMessageContaining(accountId);

            // Same-account guard fires BEFORE any repository call
            verify(accountRepository, never()).findById(any(UUID.class));
            verify(accountRepository, never()).saveAll(anyList());
            verify(transactionRepository, never()).save(any());
        }

        @Test
        @DisplayName("null amount on TransferRequest fails Bean Validation with ConstraintViolation")
        void null_amount_fails_bean_validation() {
            TransferRequest req = new TransferRequest(accountId, otherAccountId, null);

            Set<ConstraintViolation<TransferRequest>> violations = validator.validate(req);

            assertThat(violations).isNotEmpty();
            assertThat(violations)
                    .extracting(cv -> cv.getPropertyPath().toString())
                    .contains("amount");
        }

        @Test
        @DisplayName("transfer with insufficient funds throws InsufficientFundsException — both balances unchanged")
        void transfer_overdraft_throws_InsufficientFundsException() {
            // Arrange — source has 500, attempt to transfer 500.01
            BigDecimal overdraft = new BigDecimal("500.01");
            TransferRequest req  = new TransferRequest(accountId, otherAccountId, overdraft);

            // Act & Assert
            assertThatThrownBy(() -> accountService.transfer(req))
                    .isInstanceOf(InsufficientFundsException.class)
                    .hasMessageContaining("500");

            // Both account balances must be completely unchanged
            assertThat(account.getBalance()).isEqualByComparingTo("500.00");
            assertThat(otherAccount.getBalance()).isEqualByComparingTo("200.00");

            verify(accountRepository, never()).saveAll(anyList());
            verify(transactionRepository, never()).save(any());
        }

        @Test
        @DisplayName("transfer from non-existent source throws AccountNotFoundException")
        void transfer_from_non_existent_source_throws_AccountNotFoundException() {
            String unknownId = UUID.randomUUID().toString();
            // Unknown source: override stub so ANY findById miss comes back empty
            when(accountRepository.findById(any(UUID.class))).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    accountService.transfer(new TransferRequest(unknownId, otherAccountId, new BigDecimal("50.00"))))
                    .isInstanceOf(AccountNotFoundException.class);

            verify(accountRepository, never()).saveAll(anyList());
            verify(transactionRepository, never()).save(any());
        }

        @Test
        @DisplayName("blank fromAccountId on TransferRequest fails Bean Validation")
        void blank_fromAccountId_fails_bean_validation() {
            TransferRequest req = new TransferRequest("", otherAccountId, new BigDecimal("100.00"));

            Set<ConstraintViolation<TransferRequest>> violations = validator.validate(req);

            assertThat(violations).isNotEmpty();
            assertThat(violations)
                    .extracting(cv -> cv.getPropertyPath().toString())
                    .contains("fromAccountId");
        }
    }

    // =========================================================================
    // TransactionType helper behaviour
    // =========================================================================

    @Nested
    @DisplayName("TransactionType isDebit / isCredit helpers")
    class TransactionTypeHelpers {

        @Test
        @DisplayName("DEPOSIT and TRANSFER_IN are credit types")
        void credit_types() {
            assertThat(com.banking.domain.TransactionType.DEPOSIT.isCredit()).isTrue();
            assertThat(com.banking.domain.TransactionType.TRANSFER_IN.isCredit()).isTrue();
            assertThat(com.banking.domain.TransactionType.WITHDRAWAL.isCredit()).isFalse();
            assertThat(com.banking.domain.TransactionType.TRANSFER_OUT.isCredit()).isFalse();
        }

        @Test
        @DisplayName("WITHDRAWAL and TRANSFER_OUT are debit types")
        void debit_types() {
            assertThat(com.banking.domain.TransactionType.WITHDRAWAL.isDebit()).isTrue();
            assertThat(com.banking.domain.TransactionType.TRANSFER_OUT.isDebit()).isTrue();
            assertThat(com.banking.domain.TransactionType.DEPOSIT.isDebit()).isFalse();
            assertThat(com.banking.domain.TransactionType.TRANSFER_IN.isDebit()).isFalse();
        }
    }
}
