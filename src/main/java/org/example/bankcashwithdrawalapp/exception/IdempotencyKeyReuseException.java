package org.example.bankcashwithdrawalapp.exception;

public class IdempotencyKeyReuseException extends RuntimeException {
    public IdempotencyKeyReuseException() {
        super("Idempotency key has already been used with a different withdrawal request");
    }
}
