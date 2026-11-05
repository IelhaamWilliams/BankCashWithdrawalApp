package org.example.bankcashwithdrawalapp.repository;

import org.example.bankcashwithdrawalapp.event.WithdrawalEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class WithdrawalIdempotencyRepository {
    private final JdbcTemplate jdbcTemplate;

    public WithdrawalIdempotencyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<StoredWithdrawal> find(Long accountId, String idempotencyKey) {
        List<StoredWithdrawal> withdrawals = jdbcTemplate.query(
                """
                SELECT amount, event_id, status, occurred_at
                FROM withdrawal_idempotency
                WHERE account_id = ? AND idempotency_key = ?
                """,
                (resultSet, rowNumber) -> new StoredWithdrawal(
                        resultSet.getBigDecimal("amount"),
                        new WithdrawalEvent(
                                UUID.fromString(resultSet.getString("event_id")),
                                accountId,
                                resultSet.getBigDecimal("amount"),
                                resultSet.getString("status"),
                                resultSet.getTimestamp("occurred_at").toInstant()
                        )
                ),
                accountId,
                idempotencyKey
        );
        return withdrawals.stream().findFirst();
    }

    public void save(String idempotencyKey, WithdrawalEvent event) {
        jdbcTemplate.update(
                """
                INSERT INTO withdrawal_idempotency
                    (account_id, idempotency_key, amount, event_id, status, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                event.accountId(),
                idempotencyKey,
                event.amount(),
                event.eventId().toString(),
                event.status(),
                Timestamp.from(event.occurredAt())
        );
    }

    public record StoredWithdrawal(BigDecimal amount, WithdrawalEvent event) {
    }
}
