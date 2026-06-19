package com.banking.controller;

import com.banking.dto.AccountResponse;
import com.banking.dto.CreateAccountRequest;
import com.banking.dto.DepositRequest;
import com.banking.dto.WithdrawRequest;
import com.banking.exception.AccountNotFoundException;
import com.banking.exception.GlobalExceptionHandler;
import com.banking.exception.InsufficientFundsException;
import com.banking.service.AccountService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.math.BigDecimal;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Controller slice tests built with a standalone MockMvc setup
 * (MockMvcBuilders.standaloneSetup) rather than @WebMvcTest.
 *
 * @WebMvcTest(AccountController.class) was deliberately avoided here: it
 * loads a Spring context that requires every collaborator of the controller
 * to be satisfied. AccountController takes a real AccountService, and
 * without a @MockBean for it, context startup fails with
 * UnsatisfiedDependencyException — which fails every test in the class
 * before any test body even runs. That was the root cause of the local
 * failures.
 *
 * The standalone setup below builds a minimal MVC dispatcher around a
 * Mockito-mocked AccountService and the real GlobalExceptionHandler,
 * avoiding a full Spring context entirely — faster and immune to the
 * missing-bean problem.
 */
@ExtendWith(MockitoExtension.class)
class AccountControllerTest {

    @Mock
    private AccountService accountService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        AccountController controller = new AccountController(accountService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(new LocalValidatorFactoryBean())   // standalone setup does not auto-wire @Valid
                .build();
    }

    @Test
    @DisplayName("POST /accounts returns 201 with the created account")
    void createAccount_returns201() throws Exception {
        // Arrange
        UUID id = UUID.randomUUID();
        AccountResponse stubResponse = new AccountResponse(id.toString(), new BigDecimal("1000.00"));
        when(accountService.createAccount(any(CreateAccountRequest.class))).thenReturn(stubResponse);

        // Act & Assert
        mockMvc.perform(post("/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"initialBalance\": 1000.00}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountId").value(id.toString()))
                .andExpect(jsonPath("$.balance").value(1000.00));

        verify(accountService).createAccount(any(CreateAccountRequest.class));
    }

    @Test
    @DisplayName("POST /accounts with negative initialBalance returns 400")
    void createAccount_negativeBalance_returns400() throws Exception {
        mockMvc.perform(post("/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"initialBalance\": -50.00}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());

        verifyNoInteractions(accountService);
    }

    @Test
    @DisplayName("POST /accounts/{id}/deposit returns 200 with updated balance")
    void deposit_returns200() throws Exception {
        // Arrange
        String accountId = UUID.randomUUID().toString();
        AccountResponse stubResponse = new AccountResponse(accountId, new BigDecimal("1500.00"));
        when(accountService.deposit(eq(accountId), any(DepositRequest.class))).thenReturn(stubResponse);

        // Act & Assert
        mockMvc.perform(post("/accounts/{id}/deposit", accountId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": 500.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(1500.00));

        verify(accountService).deposit(eq(accountId), any(DepositRequest.class));
    }

    @Test
    @DisplayName("POST /accounts/{id}/deposit with non-existent account returns 404")
    void deposit_accountNotFound_returns404() throws Exception {
        String unknownId = UUID.randomUUID().toString();
        when(accountService.deposit(eq(unknownId), any(DepositRequest.class)))
                .thenThrow(new AccountNotFoundException(unknownId));

        mockMvc.perform(post("/accounts/{id}/deposit", unknownId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": 100.00}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("POST /accounts/{id}/withdraw returns 200 with updated balance")
    void withdraw_returns200() throws Exception {
        // Arrange
        String accountId = UUID.randomUUID().toString();
        AccountResponse stubResponse = new AccountResponse(accountId, new BigDecimal("700.00"));
        when(accountService.withdraw(eq(accountId), any(WithdrawRequest.class))).thenReturn(stubResponse);

        // Act & Assert
        mockMvc.perform(post("/accounts/{id}/withdraw", accountId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": 300.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(700.00));

        verify(accountService).withdraw(eq(accountId), any(WithdrawRequest.class));
    }

    @Test
    @DisplayName("POST /accounts/{id}/withdraw with insufficient funds returns 400")
    void withdraw_insufficientFunds_returns400() throws Exception {
        String accountId = UUID.randomUUID().toString();
        when(accountService.withdraw(eq(accountId), any(WithdrawRequest.class)))
                .thenThrow(new InsufficientFundsException("Insufficient funds: balance is 100.00 but withdrawal requested 500.00"));

        mockMvc.perform(post("/accounts/{id}/withdraw", accountId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": 500.00}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }
}
