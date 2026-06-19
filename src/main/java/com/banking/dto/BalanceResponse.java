package com.banking.dto;

import com.banking.domain.Account;
import java.math.BigDecimal;

public class BalanceResponse {

    private final String     accountId;
    private final BigDecimal balance;

    private BalanceResponse(String accountId, BigDecimal balance) {
        this.accountId = accountId;
        this.balance   = balance;
    }

    /**
     * Maps an {@link Account} entity to a balance response DTO.
     * The entity never escapes the service layer — callers receive this DTO only.
     */
    public static BalanceResponse from(Account account) {
        return new BalanceResponse(
                account.getId().toString(),
                account.getBalance()
        );
    }

    public String     getAccountId() { return accountId; }
    public BigDecimal getBalance()   { return balance; }
}
