package org.example.bankcashwithdrawalapp.service;

import org.example.bankcashwithdrawalapp.messaging.WithdrawalEventPublisher;
import org.example.bankcashwithdrawalapp.repository.OutboxRepository;
import org.example.bankcashwithdrawalapp.repository.OutboxRepository.OutboxMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxDispatcherTest {
    private static final OutboxMessage MESSAGE =
            new OutboxMessage("event-123", "{\"accountId\":101}", 1);

    private OutboxRepository outboxRepository;
    private WithdrawalEventPublisher eventPublisher;
    private OutboxDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        outboxRepository = mock(OutboxRepository.class);
        eventPublisher = mock(WithdrawalEventPublisher.class);
        dispatcher = new OutboxDispatcher(outboxRepository, eventPublisher, 10, 60, 1000);
    }

    @Test
    void dispatchPublishesClaimedEventsAndMarksThemPublished() {
        when(outboxRepository.claimBatch(10, Duration.ofSeconds(60)))
                .thenReturn(List.of(MESSAGE));

        dispatcher.dispatchPendingEvents();

        verify(eventPublisher).publish(MESSAGE.eventId(), MESSAGE.payload());
        verify(outboxRepository).markPublished(MESSAGE.eventId());
        verify(outboxRepository, never()).scheduleRetry(eq(MESSAGE), any(RuntimeException.class));
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
        verify(outboxRepository, never()).markPublished(MESSAGE.eventId());
    }

    @Test
    void constructorRejectsInvalidDispatcherSettings() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new OutboxDispatcher(outboxRepository, eventPublisher, 0, 60, 1000)
        );
    }
}
