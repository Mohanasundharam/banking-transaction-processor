package com.banking.controller;

import com.banking.dto.AccountResponse;
import com.banking.dto.BalanceResponse;
import com.banking.dto.CreateAccountRequest;
import com.banking.dto.DepositRequest;
import com.banking.dto.TransactionHistoryResponse;
import com.banking.dto.TransferRequest;
import com.banking.dto.WithdrawRequest;
import com.banking.service.AccountService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    // POST /accounts
    @PostMapping
    public ResponseEntity<AccountResponse> createAccount(
            @Valid @RequestBody CreateAccountRequest request) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(accountService.createAccount(request));
    }

    // POST /accounts/transfer
    // Declared BEFORE /{id}/... mappings so Spring MVC does not treat
    // the literal segment "transfer" as a path variable value.
    @PostMapping("/transfer")
    public ResponseEntity<Void> transfer(
            @Valid @RequestBody TransferRequest request) {
        accountService.transfer(request);
        return ResponseEntity.ok().build();
    }

    // POST /accounts/{id}/deposit
    @PostMapping("/{id}/deposit")
    public ResponseEntity<AccountResponse> deposit(
            @PathVariable String id,
            @Valid @RequestBody DepositRequest request) {
        return ResponseEntity.ok(accountService.deposit(id, request));
    }

    // POST /accounts/{id}/withdraw
    @PostMapping("/{id}/withdraw")
    public ResponseEntity<AccountResponse> withdraw(
            @PathVariable String id,
            @Valid @RequestBody WithdrawRequest request) {
        return ResponseEntity.ok(accountService.withdraw(id, request));
    }

    // GET /accounts/{id}/balance
    @GetMapping("/{id}/balance")
    public ResponseEntity<BalanceResponse> getBalance(
            @PathVariable String id) {
        // AccountService.getBalance throws AccountNotFoundException → 404
        // via GlobalExceptionHandler if the account does not exist.
        return ResponseEntity.ok(accountService.getBalance(id));
    }

    // GET /accounts/{id}/transactions
    @GetMapping("/{id}/transactions")
    public ResponseEntity<List<TransactionHistoryResponse>> getTransactionHistory(
            @PathVariable String id) {
        // Returns 200 with an empty array when the account exists but has no
        // transactions. Returns 404 when the account itself does not exist.
        return ResponseEntity.ok(accountService.getTransactionHistory(id));
    }
}
