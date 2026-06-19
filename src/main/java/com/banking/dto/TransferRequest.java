package com.banking.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

public class TransferRequest {

    @NotBlank(message = "Source account ID must not be blank")
    private String fromAccountId;

    @NotBlank(message = "Destination account ID must not be blank")
    private String toAccountId;

    @NotNull(message = "Amount must not be null")
    @Positive(message = "Amount must be greater than zero")
    private BigDecimal amount;

    public TransferRequest() {}

    public TransferRequest(String fromAccountId, String toAccountId, BigDecimal amount) {
        this.fromAccountId = fromAccountId;
        this.toAccountId   = toAccountId;
        this.amount        = amount;
    }

    public String     getFromAccountId() { return fromAccountId; }
    public String     getToAccountId()   { return toAccountId; }
    public BigDecimal getAmount()        { return amount; }

    public void setFromAccountId(String fromAccountId) { this.fromAccountId = fromAccountId; }
    public void setToAccountId(String toAccountId)     { this.toAccountId   = toAccountId; }
    public void setAmount(BigDecimal amount)            { this.amount        = amount; }
}
