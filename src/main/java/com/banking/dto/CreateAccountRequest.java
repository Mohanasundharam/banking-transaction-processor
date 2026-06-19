package com.banking.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;

public class CreateAccountRequest {

    @NotNull(message = "Initial balance must not be null")
    @PositiveOrZero(message = "Initial balance must be zero or positive")
    private BigDecimal initialBalance;

    public CreateAccountRequest() {}

    public CreateAccountRequest(BigDecimal initialBalance) {
        this.initialBalance = initialBalance;
    }

    public BigDecimal getInitialBalance() { return initialBalance; }

    public void setInitialBalance(BigDecimal initialBalance) { this.initialBalance = initialBalance; }
}
