package com.banking.exception;

public class SameAccountTransferException extends RuntimeException {

    public SameAccountTransferException(String accountId) {
        super("Transfer source and destination must be different accounts, got: " + accountId);
    }
}
