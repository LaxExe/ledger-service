package com.example.ledger.exception;

public class InsufficientFundsException extends RuntimeException {
    public InsufficientFundsException(Long accountId, Long requiredCents, Long actualCents) {
        super(String.format("Account %d has insufficient funds. Required: %d cents, Available: %d cents",
                accountId, requiredCents, actualCents));
    }
}
