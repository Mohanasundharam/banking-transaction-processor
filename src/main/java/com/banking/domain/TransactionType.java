package com.banking.domain;

/**
 * Classifies every ledger entry in the transaction table.
 *
 * <ul>
 *   <li>{@link #DEPOSIT}      – money credited to an account from outside the system.</li>
 *   <li>{@link #WITHDRAWAL}   – money debited from an account to outside the system.</li>
 *   <li>{@link #TRANSFER_OUT} – debit leg of an internal account-to-account transfer.</li>
 *   <li>{@link #TRANSFER_IN}  – credit leg of an internal account-to-account transfer.</li>
 * </ul>
 *
 * <p><strong>Amount sign convention:</strong> {@link Transaction#getAmount()} is always stored
 * as a positive value. Use {@link #isDebit()} / {@link #isCredit()} to determine the
 * effective sign when computing running balances or presenting signed amounts in a statement.
 *
 * <p>TRANSFER_OUT and TRANSFER_IN records that belong to the same transfer share an
 * identical {@link Transaction#getReference()} value so both legs can always be
 * retrieved and correlated.
 */
public enum TransactionType {
    DEPOSIT,
    WITHDRAWAL,
    TRANSFER_IN,
    TRANSFER_OUT;

    /** Returns {@code true} for types that reduce an account balance. */
    public boolean isDebit() {
        return this == WITHDRAWAL || this == TRANSFER_OUT;
    }

    /** Returns {@code true} for types that increase an account balance. */
    public boolean isCredit() {
        return this == DEPOSIT || this == TRANSFER_IN;
    }
}
