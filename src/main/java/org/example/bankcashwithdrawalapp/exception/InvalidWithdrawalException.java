package org.example.bankcashwithdrawalapp.exception;

public class InvalidWithdrawalException extends RuntimeException {
    public InvalidWithdrawalException(String message) {
        super(message);
    }
}
