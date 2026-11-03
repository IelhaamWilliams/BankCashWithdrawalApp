package org.example.bankcashwithdrawalapp.rest;

import org.example.bankcashwithdrawalapp.event.WithdrawalEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record WithdrawalResponse(
        UUID eventId,
        Long accountId,
        BigDecimal amount,
        String status,
        Instant occurredAt
) {
    public static WithdrawalResponse from(WithdrawalEvent event) {
        return new WithdrawalResponse(
                event.eventId(),
                event.accountId(),
                event.amount(),
                event.status(),
                event.occurredAt()
        );
    }
}
