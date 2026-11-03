package org.example.bankcashwithdrawalapp.service;

import org.example.bankcashwithdrawalapp.messaging.WithdrawalEventPublisher;
import org.example.bankcashwithdrawalapp.repository.OutboxRepository;
import org.example.bankcashwithdrawalapp.repository.OutboxRepository.OutboxMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConditionalOnProperty(name = "app.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxDispatcher {
    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final OutboxRepository outboxRepository;
    private final WithdrawalEventPublisher eventPublisher;
    private final int batchSize;
    private final Duration leaseDuration;

    public OutboxDispatcher(
            OutboxRepository outboxRepository,
            WithdrawalEventPublisher eventPublisher,
            @Value("${app.outbox.batch-size:10}") int batchSize,
            @Value("${app.outbox.lease-seconds:60}") long leaseSeconds,
            @Value("${app.outbox.poll-interval-ms:1000}") long pollIntervalMillis
    ) {
        if (batchSize < 1 || leaseSeconds < 1 || pollIntervalMillis < 1) {
            throw new IllegalArgumentException("Outbox batch size, lease duration, and poll interval must be positive");
        }
        this.outboxRepository = outboxRepository;
        this.eventPublisher = eventPublisher;
        this.batchSize = batchSize;
        this.leaseDuration = Duration.ofSeconds(leaseSeconds);
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:1000}")
    public void dispatchPendingEvents() {
        for (OutboxMessage message : outboxRepository.claimBatch(batchSize, leaseDuration)) {
            try {
                eventPublisher.publish(message.eventId(), message.payload());
            } catch (RuntimeException exception) {
                outboxRepository.scheduleRetry(message, exception);
                LOGGER.warn("SNS publish for outbox event {} failed; retry scheduled", message.eventId(), exception);
                continue;
            }
            outboxRepository.markPublished(message.eventId());
        }
    }
}
