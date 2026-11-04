package org.example.bankcashwithdrawalapp.exception;

public class ConcurrentWithdrawalException extends RuntimeException {
    public ConcurrentWithdrawalException(Long accountId) {
        super("Account %d was updated concurrently; retry the withdrawal".formatted(accountId));
    }
}
