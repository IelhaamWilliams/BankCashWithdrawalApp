package org.example.bankcashwithdrawalapp.exception;

public class InsufficientFundsException extends RuntimeException {
    public InsufficientFundsException(Long accountId) {
        super("Account %d has insufficient funds".formatted(accountId));
    }
}
