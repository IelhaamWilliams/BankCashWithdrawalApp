package org.example.bankcashwithdrawalapp.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.example.bankcashwithdrawalapp.messaging.WithdrawalEventPublisher;
import org.example.bankcashwithdrawalapp.repository.OutboxRepository;
import org.example.bankcashwithdrawalapp.repository.OutboxRepository.OutboxMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxDispatcherTest {
    private static final OutboxMessage MESSAGE =
            new OutboxMessage("event-123", "{\"accountId\":101}", 1, "claim-token");

    private OutboxRepository outboxRepository;
    private WithdrawalEventPublisher eventPublisher;
    private SimpleMeterRegistry meterRegistry;
    private OutboxDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        outboxRepository = mock(OutboxRepository.class);
        eventPublisher = mock(WithdrawalEventPublisher.class);
        meterRegistry = new SimpleMeterRegistry();
        dispatcher = new OutboxDispatcher(
                outboxRepository, eventPublisher, meterRegistry, 10, 60, 1000
        );
    }

    @Test
    void dispatchPublishesClaimedEventsAndMarksThemPublished() {
        when(outboxRepository.claimBatch(10, Duration.ofSeconds(60)))
                .thenReturn(List.of(MESSAGE));

        dispatcher.dispatchPendingEvents();

        verify(eventPublisher).publish(MESSAGE.eventId(), MESSAGE.payload());
        verify(outboxRepository).markPublished(MESSAGE.eventId(), MESSAGE.lockToken());
        verify(outboxRepository, never()).scheduleRetry(eq(MESSAGE), any(RuntimeException.class));
        assertEquals(1.0, meterRegistry.get("bank.outbox.publish")
                .tag("outcome", "published").counter().count());
        assertEquals(1.0, meterRegistry.get("bank.outbox.claimed").counter().count());
    }

    @Test
    void dispatchSchedulesRetryWhenPublishingFails() {
        IllegalStateException failure = new IllegalStateException("SNS unavailable");
        when(outboxRepository.claimBatch(10, Duration.ofSeconds(60)))
                .thenReturn(List.of(MESSAGE));
        org.mockito.Mockito.doThrow(failure)
                .when(eventPublisher).publish(MESSAGE.eventId(), MESSAGE.payload());

        dispatcher.dispatchPendingEvents();

        verify(outboxRepository).scheduleRetry(MESSAGE, failure);
        verify(outboxRepository, never()).markPublished(MESSAGE.eventId(), MESSAGE.lockToken());
        assertEquals(1.0, meterRegistry.get("bank.outbox.publish")
                .tag("outcome", "failed").counter().count());
        assertEquals(1.0, meterRegistry.get("bank.outbox.retry_scheduled").counter().count());
    }

    @Test
    void constructorRejectsInvalidDispatcherSettings() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new OutboxDispatcher(
                        outboxRepository, eventPublisher, new SimpleMeterRegistry(), 0, 60, 1000
                )
        );
    }
}
