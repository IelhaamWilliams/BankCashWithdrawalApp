package org.example.bankcashwithdrawalapp.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record WithdrawalEvent(
        UUID eventId,
        Long accountId,
        BigDecimal amount,
        String status,
        Instant occurredAt
) {
}
