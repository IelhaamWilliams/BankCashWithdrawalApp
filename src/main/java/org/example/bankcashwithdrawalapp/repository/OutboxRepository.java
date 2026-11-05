package org.example.bankcashwithdrawalapp.repository;

import org.example.bankcashwithdrawalapp.event.WithdrawalEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
public class OutboxRepository {
    private static final long MAX_RETRY_DELAY_SECONDS = 300;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public OutboxRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void enqueue(WithdrawalEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            jdbcTemplate.update(
                    "INSERT INTO withdrawal_outbox (event_id, payload) VALUES (?, ?)",
                    event.eventId().toString(),
                    payload
            );
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not serialize withdrawal event", exception);
        }
    }

    public List<OutboxMessage> claimBatch(int batchSize, Duration leaseDuration) {
        Instant now = Instant.now();
        Timestamp nowTimestamp = Timestamp.from(now);
        List<OutboxMessage> candidates = jdbcTemplate.query(
                """
                SELECT event_id, payload, attempt_count
                FROM withdrawal_outbox
                WHERE (status = 'PENDING' AND next_attempt_at <= ?)
                   OR (status = 'PROCESSING' AND locked_until <= ?)
                ORDER BY created_at
                LIMIT ?
                """,
                (resultSet, rowNumber) -> new OutboxMessage(
                        resultSet.getString("event_id"),
                        resultSet.getString("payload"),
                        resultSet.getInt("attempt_count") + 1,
                        null
                ),
                nowTimestamp,
                nowTimestamp,
                batchSize
        );

        List<OutboxMessage> claimed = new ArrayList<>();
        Timestamp lockedUntil = Timestamp.from(now.plus(leaseDuration));
        for (OutboxMessage candidate : candidates) {
            String lockToken = UUID.randomUUID().toString();
            int rowsUpdated = jdbcTemplate.update(
                    """
                    UPDATE withdrawal_outbox
                    SET status = 'PROCESSING',
                        attempt_count = attempt_count + 1,
                        locked_until = ?,
                        lock_token = ?
                    WHERE event_id = ?
                      AND ((status = 'PENDING' AND next_attempt_at <= ?)
                        OR (status = 'PROCESSING' AND locked_until <= ?))
                    """,
                    lockedUntil,
                    lockToken,
                    candidate.eventId(),
                    nowTimestamp,
                    nowTimestamp
            );
            if (rowsUpdated == 1) {
                claimed.add(new OutboxMessage(
                        candidate.eventId(),
                        candidate.payload(),
                        candidate.attemptCount(),
                        lockToken
                ));
            }
        }
        return claimed;
    }

    public void markPublished(String eventId, String lockToken) {
        int rowsUpdated = jdbcTemplate.update(
                """
                UPDATE withdrawal_outbox
                SET status = 'PUBLISHED', published_at = ?, locked_until = NULL,
                    lock_token = NULL, last_error = NULL
                WHERE event_id = ? AND status = 'PROCESSING' AND lock_token = ?
                """,
                Timestamp.from(Instant.now()),
                eventId,
                lockToken
        );
        requireClaimedEventUpdate(eventId, rowsUpdated);
    }

    public void scheduleRetry(OutboxMessage message, RuntimeException failure) {
        long exponent = Math.min(message.attemptCount() - 1L, 8L);
        long retryDelaySeconds = Math.min(1L << exponent, MAX_RETRY_DELAY_SECONDS);
        String errorMessage = failure.getMessage() == null
                ? failure.getClass().getSimpleName()
                : failure.getMessage();
        if (errorMessage.length() > 1000) {
            errorMessage = errorMessage.substring(0, 1000);
        }

        int rowsUpdated = jdbcTemplate.update(
                """
                UPDATE withdrawal_outbox
                SET status = 'PENDING',
                    next_attempt_at = ?,
                    locked_until = NULL,
                    lock_token = NULL,
                    last_error = ?
                WHERE event_id = ? AND status = 'PROCESSING' AND lock_token = ?
                """,
                Timestamp.from(Instant.now().plusSeconds(retryDelaySeconds)),
                errorMessage,
                message.eventId(),
                message.lockToken()
        );
        requireClaimedEventUpdate(message.eventId(), rowsUpdated);
    }

    private void requireClaimedEventUpdate(String eventId, int rowsUpdated) {
        if (rowsUpdated != 1) {
            throw new IllegalStateException("Outbox event %s is no longer claimed".formatted(eventId));
        }
    }

    public record OutboxMessage(String eventId, String payload, int attemptCount, String lockToken) {
    }
}
