package org.example.bankcashwithdrawalapp.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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
import java.util.List;

@Component
@ConditionalOnProperty(name = "app.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxDispatcher {
    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final OutboxRepository outboxRepository;
    private final WithdrawalEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;
    private final int batchSize;
    private final Duration leaseDuration;

    public OutboxDispatcher(
            OutboxRepository outboxRepository,
            WithdrawalEventPublisher eventPublisher,
            MeterRegistry meterRegistry,
            @Value("${app.outbox.batch-size:10}") int batchSize,
            @Value("${app.outbox.lease-seconds:60}") long leaseSeconds,
            @Value("${app.outbox.poll-interval-ms:1000}") long pollIntervalMillis
    ) {
        if (batchSize < 1 || leaseSeconds < 1 || pollIntervalMillis < 1) {
            throw new IllegalArgumentException("Outbox batch size, lease duration, and poll interval must be positive");
        }
        this.outboxRepository = outboxRepository;
        this.eventPublisher = eventPublisher;
        this.meterRegistry = meterRegistry;
        this.batchSize = batchSize;
        this.leaseDuration = Duration.ofSeconds(leaseSeconds);
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:1000}")
    public void dispatchPendingEvents() {
        List<OutboxMessage> claimedMessages = outboxRepository.claimBatch(batchSize, leaseDuration);
        meterRegistry.counter("bank.outbox.claimed").increment(claimedMessages.size());
        if (!claimedMessages.isEmpty()) {
            LOGGER.info("Claimed {} outbox events", claimedMessages.size());
        }
        for (OutboxMessage message : claimedMessages) {
            Timer.Sample publishTimer = Timer.start(meterRegistry);
            try {
                eventPublisher.publish(message.eventId(), message.payload());
            } catch (RuntimeException exception) {
                meterRegistry.counter("bank.outbox.publish", "outcome", "failed").increment();
                publishTimer.stop(Timer.builder("bank.outbox.publish.duration")
                        .tag("outcome", "failed")
                        .register(meterRegistry));
                try {
                    outboxRepository.scheduleRetry(message, exception);
                } catch (RuntimeException stateUpdateFailure) {
                    meterRegistry.counter("bank.outbox.state_update", "outcome", "failed").increment();
                    LOGGER.error("Could not schedule retry for outbox event {}", message.eventId(),
                            stateUpdateFailure);
                    throw stateUpdateFailure;
                }
                meterRegistry.counter("bank.outbox.retry_scheduled").increment();
                LOGGER.warn("SNS publish for outbox event {} failed; retry scheduled", message.eventId(), exception);
                continue;
            }
            publishTimer.stop(Timer.builder("bank.outbox.publish.duration")
                    .tag("outcome", "published")
                    .register(meterRegistry));
            meterRegistry.counter("bank.outbox.publish", "outcome", "published").increment();
            try {
                outboxRepository.markPublished(message.eventId(), message.lockToken());
            } catch (RuntimeException stateUpdateFailure) {
                meterRegistry.counter("bank.outbox.state_update", "outcome", "failed").increment();
                LOGGER.error("Could not mark outbox event {} as published; it may be delivered again",
                        message.eventId(), stateUpdateFailure);
                throw stateUpdateFailure;
            }
            LOGGER.info("Published outbox event eventId={}", message.eventId());
        }
    }
}
