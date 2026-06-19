package com.banking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Full-stack integration tests using Spring Boot's test slice with H2.
 *
 * Every test method is wrapped in a transaction that rolls back on completion
 * (@Transactional at the class level), so each test starts from a clean DB
 * state without manual teardown.
 *
 * HTTP round-trips are made via MockMvc (no real network), but the full
 * Spring context — including JPA, H2, transaction management, and the
 * GlobalExceptionHandler — is loaded.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional           // rolls back after every test — clean state per test
class AccountIntegrationTest {

    @Autowired private MockMvc     mockMvc;
    @Autowired private ObjectMapper objectMapper;

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** POST /accounts and return the new accountId string */
    private String createAccount(BigDecimal initialBalance) throws Exception {
        String body = String.format("{\"initialBalance\": %s}", initialBalance.toPlainString());

        MvcResult result = mockMvc.perform(post("/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.get("accountId").asText();
    }

    /** POST /accounts/{id}/deposit */
    private void deposit(String accountId, BigDecimal amount) throws Exception {
        String body = String.format("{\"amount\": %s}", amount.toPlainString());
        mockMvc.perform(post("/accounts/{id}/deposit", accountId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    /** POST /accounts/{id}/withdraw */
    private void withdraw(String accountId, BigDecimal amount) throws Exception {
        String body = String.format("{\"amount\": %s}", amount.toPlainString());
        mockMvc.perform(post("/accounts/{id}/withdraw", accountId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    /** POST /accounts/transfer */
    private void transfer(String fromId, String toId, BigDecimal amount) throws Exception {
        String body = String.format(
                "{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":%s}",
                fromId, toId, amount.toPlainString());
        mockMvc.perform(post("/accounts/transfer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    /** GET /accounts/{id}/balance → balance as BigDecimal */
    private BigDecimal getBalance(String accountId) throws Exception {
        MvcResult result = mockMvc.perform(get("/accounts/{id}/balance", accountId))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.get("balance").decimalValue();
    }

    /** GET /accounts/{id}/transactions → parsed JsonNode array */
    private JsonNode getTransactions(String accountId) throws Exception {
        MvcResult result = mockMvc.perform(get("/accounts/{id}/transactions", accountId))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    // =========================================================================
    // Full deposit flow
    // =========================================================================

    @Nested
    @DisplayName("Deposit flow")
    class DepositFlow {

        @Test
        @DisplayName("create account → deposit → balance reflects deposit via GET /balance")
        void full_deposit_flow_balance_reflects_deposit() throws Exception {
            // Arrange
            String accountId = createAccount(new BigDecimal("1000.00"));

            // Act
            deposit(accountId, new BigDecimal("250.00"));

            // Assert — GET /balance returns the updated figure
            BigDecimal balance = getBalance(accountId);
            assertThat(balance).isEqualByComparingTo("1250.00");
        }

        @Test
        @DisplayName("multiple deposits accumulate correctly")
        void multiple_deposits_accumulate() throws Exception {
            String accountId = createAccount(BigDecimal.ZERO);

            deposit(accountId, new BigDecimal("100.00"));
            deposit(accountId, new BigDecimal("200.00"));
            deposit(accountId, new BigDecimal("50.00"));

            assertThat(getBalance(accountId)).isEqualByComparingTo("350.00");
        }

        @Test
        @DisplayName("deposit with negative amount returns 400")
        void negative_deposit_returns_400() throws Exception {
            String accountId = createAccount(new BigDecimal("500.00"));
            String body = "{\"amount\": -1}";

            mockMvc.perform(post("/accounts/{id}/deposit", accountId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").exists());
        }

        @Test
        @DisplayName("deposit to unknown account returns 404")
        void deposit_to_unknown_account_returns_404() throws Exception {
            String unknownId = java.util.UUID.randomUUID().toString();
            String body      = "{\"amount\": 100}";

            mockMvc.perform(post("/accounts/{id}/deposit", unknownId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").exists());
        }
    }

    // =========================================================================
    // Full transfer flow
    // =========================================================================

    @Nested
    @DisplayName("Transfer flow")
    class TransferFlow {

        @Test
        @DisplayName("create two accounts → transfer → both balances correct via GET /balance")
        void full_transfer_flow_both_balances_correct() throws Exception {
            // Arrange
            String sourceId      = createAccount(new BigDecimal("1000.00"));
            String destinationId = createAccount(new BigDecimal("500.00"));

            // Act
            transfer(sourceId, destinationId, new BigDecimal("300.00"));

            // Assert
            assertThat(getBalance(sourceId)).isEqualByComparingTo("700.00");
            assertThat(getBalance(destinationId)).isEqualByComparingTo("800.00");
        }

        @Test
        @DisplayName("transfer with insufficient funds returns 400 — both balances unchanged")
        void transfer_insufficient_funds_returns_400_balances_unchanged() throws Exception {
            String sourceId      = createAccount(new BigDecimal("200.00"));
            String destinationId = createAccount(new BigDecimal("100.00"));

            String body = String.format(
                    "{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":200.01}",
                    sourceId, destinationId);

            mockMvc.perform(post("/accounts/transfer")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").exists());

            // Balances must be completely unchanged
            assertThat(getBalance(sourceId)).isEqualByComparingTo("200.00");
            assertThat(getBalance(destinationId)).isEqualByComparingTo("100.00");
        }

        @Test
        @DisplayName("transfer to same account returns 400")
        void transfer_same_account_returns_400() throws Exception {
            String accountId = createAccount(new BigDecimal("500.00"));
            String body = String.format(
                    "{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":100}",
                    accountId, accountId);

            mockMvc.perform(post("/accounts/transfer")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").exists());
        }

        @Test
        @DisplayName("chained transfers — money moves through a chain of three accounts correctly")
        void chained_transfers_balance_correctly() throws Exception {
            String a = createAccount(new BigDecimal("1000.00"));
            String b = createAccount(BigDecimal.ZERO);
            String c = createAccount(BigDecimal.ZERO);

            transfer(a, b, new BigDecimal("400.00")); // a=600, b=400
            transfer(b, c, new BigDecimal("150.00")); // b=250, c=150

            assertThat(getBalance(a)).isEqualByComparingTo("600.00");
            assertThat(getBalance(b)).isEqualByComparingTo("250.00");
            assertThat(getBalance(c)).isEqualByComparingTo("150.00");
        }
    }

    // =========================================================================
    // Transaction history
    // =========================================================================

    @Nested
    @DisplayName("Transaction history")
    class TransactionHistory {

        @Test
        @DisplayName("history populated correctly after deposit + withdrawal sequence")
        void history_populated_after_deposit_and_withdrawal() throws Exception {
            // Arrange
            String accountId = createAccount(new BigDecimal("1000.00"));

            // Act
            deposit(accountId, new BigDecimal("500.00"));
            withdraw(accountId, new BigDecimal("200.00"));

            // Assert — 2 transactions in history
            JsonNode txns = getTransactions(accountId);
            assertThat(txns.isArray()).isTrue();
            assertThat(txns.size()).isEqualTo(2);

            // Most recent first (timestamp DESC) → WITHDRAWAL before DEPOSIT
            assertThat(txns.get(0).get("type").asText()).isEqualTo("WITHDRAWAL");
            assertThat(txns.get(0).get("amount").decimalValue())
                    .isEqualByComparingTo("200.00");

            assertThat(txns.get(1).get("type").asText()).isEqualTo("DEPOSIT");
            assertThat(txns.get(1).get("amount").decimalValue())
                    .isEqualByComparingTo("500.00");
        }

        @Test
        @DisplayName("history contains TRANSFER_OUT and TRANSFER_IN after a transfer")
        void history_contains_transfer_entries_for_both_accounts() throws Exception {
            // Arrange
            String sourceId      = createAccount(new BigDecimal("800.00"));
            String destinationId = createAccount(new BigDecimal("200.00"));

            // Act
            transfer(sourceId, destinationId, new BigDecimal("300.00"));

            // Assert — source account shows TRANSFER_OUT
            JsonNode sourceTxns = getTransactions(sourceId);
            assertThat(sourceTxns.size()).isEqualTo(1);
            assertThat(sourceTxns.get(0).get("type").asText()).isEqualTo("TRANSFER_OUT");
            assertThat(sourceTxns.get(0).get("amount").decimalValue())
                    .isEqualByComparingTo("300.00");

            // Assert — destination account shows TRANSFER_IN
            JsonNode destTxns = getTransactions(destinationId);
            assertThat(destTxns.size()).isEqualTo(1);
            assertThat(destTxns.get(0).get("type").asText()).isEqualTo("TRANSFER_IN");
            assertThat(destTxns.get(0).get("amount").decimalValue())
                    .isEqualByComparingTo("300.00");

            // Assert — both legs share the same non-null reference UUID
            String sourceRef = sourceTxns.get(0).get("reference").asText();
            String destRef   = destTxns.get(0).get("reference").asText();
            assertThat(sourceRef).isNotBlank();
            assertThat(destRef).isEqualTo(sourceRef);
        }

        @Test
        @DisplayName("account with no transactions returns empty array, not 404")
        void empty_history_returns_200_with_empty_array() throws Exception {
            String accountId = createAccount(new BigDecimal("100.00"));

            mockMvc.perform(get("/accounts/{id}/transactions", accountId))
                    .andExpect(status().isOk())
                    .andExpect(content().json("[]"));
        }

        @Test
        @DisplayName("transaction history for unknown account returns 404")
        void unknown_account_history_returns_404() throws Exception {
            String unknownId = java.util.UUID.randomUUID().toString();

            mockMvc.perform(get("/accounts/{id}/transactions", unknownId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").exists());
        }

        @Test
        @DisplayName("history after full sequence: deposit + transfer out + withdrawal")
        void history_correct_after_mixed_sequence() throws Exception {
            // Arrange
            String primaryId = createAccount(new BigDecimal("1000.00"));
            String secondId  = createAccount(BigDecimal.ZERO);

            // Act — three operations on primaryId
            deposit(primaryId, new BigDecimal("500.00"));          // balance: 1500
            transfer(primaryId, secondId, new BigDecimal("300.00")); // balance: 1200
            withdraw(primaryId, new BigDecimal("400.00"));          // balance: 800

            // Assert — primary account has 3 transactions
            JsonNode txns = getTransactions(primaryId);
            assertThat(txns.size()).isEqualTo(3);

            // Timestamps must be in descending order (history ordered newest first)
            for (int i = 0; i < txns.size() - 1; i++) {
                String ts1 = txns.get(i).get("timestamp").asText();
                String ts2 = txns.get(i + 1).get("timestamp").asText();
                // lexicographic ISO-8601 comparison is valid for same-date timestamps
                assertThat(ts1.compareTo(ts2)).isGreaterThanOrEqualTo(0);
            }

            // Types present in history (newest-first order may vary within the same second)
            assertThat(txns).anySatisfy(tx ->
                    assertThat(tx.get("type").asText()).isEqualTo("WITHDRAWAL"));
            assertThat(txns).anySatisfy(tx ->
                    assertThat(tx.get("type").asText()).isEqualTo("TRANSFER_OUT"));
            assertThat(txns).anySatisfy(tx ->
                    assertThat(tx.get("type").asText()).isEqualTo("DEPOSIT"));

            // Final balance
            assertThat(getBalance(primaryId)).isEqualByComparingTo("800.00");
        }
    }
}
