package org.example.bankcashwithdrawalapp.exception;

public class AccountNotFoundException extends RuntimeException {
    public AccountNotFoundException(Long accountId) {
        super("Account %d was not found".formatted(accountId));
    }
}
