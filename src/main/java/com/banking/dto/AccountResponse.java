package com.banking.dto;

import com.banking.domain.Account;
import java.math.BigDecimal;

public class AccountResponse {

    private String accountId;
    private BigDecimal balance;

    public AccountResponse() {}

    public AccountResponse(String accountId, BigDecimal balance) {
        this.accountId = accountId;
        this.balance = balance;
    }

    public static AccountResponse from(Account account) {
        return new AccountResponse(
            account.getId().toString(),
            account.getBalance()
        );
    }

    public String getAccountId() { return accountId; }

    public BigDecimal getBalance() { return balance; }
}
