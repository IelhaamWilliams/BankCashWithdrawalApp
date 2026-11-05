package org.example.bankcashwithdrawalapp.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.example.bankcashwithdrawalapp.entity.Accounts;
import org.example.bankcashwithdrawalapp.exception.ConcurrentWithdrawalException;
import org.example.bankcashwithdrawalapp.event.WithdrawalEvent;
import org.example.bankcashwithdrawalapp.repository.AccountRepository;
import org.example.bankcashwithdrawalapp.repository.OutboxRepository;
import org.example.bankcashwithdrawalapp.repository.WithdrawalIdempotencyRepository;
import org.example.bankcashwithdrawalapp.repository.WithdrawalIdempotencyRepository.StoredWithdrawal;
import org.example.bankcashwithdrawalapp.repository.WithdrawalIdempotencyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringJUnitConfig(WithdrawalRetryTest.RetryTestConfiguration.class)
class WithdrawalRetryTest {
    private static final long ACCOUNT_ID = 101L;
    private static final BigDecimal AMOUNT = new BigDecimal("10.00");

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private WithdrawalIdempotencyRepository idempotencyRepository;

    @Autowired
    private WithdrawalTransactionService withdrawalService;

    @BeforeEach
    void setUp() {
        reset(accountRepository, outboxRepository, idempotencyRepository);
        when(idempotencyRepository.find(ACCOUNT_ID, "retry-test")).thenReturn(Optional.empty());
        when(accountRepository.findById(ACCOUNT_ID))
                .thenReturn(Optional.of(new Accounts(ACCOUNT_ID, new BigDecimal("100.00"))));
        when(accountRepository.debitIfVersionMatches(ACCOUNT_ID, AMOUNT, 0L))
                .thenReturn(false);
    }

    @Test
    void exhaustedConcurrentWithdrawalRetriesReturnConflictException() {
        assertThrows(
                ConcurrentWithdrawalException.class,
                () -> withdrawalService.withdraw(ACCOUNT_ID, AMOUNT, "retry-test")
        );

        verify(accountRepository, times(10)).findById(ACCOUNT_ID);
        verify(accountRepository, times(10)).debitIfVersionMatches(ACCOUNT_ID, AMOUNT, 0L);
        verifyNoInteractions(outboxRepository);
    }

    @Test
    void exhaustedDuplicateKeyRetriesReplayTheWinningSameAmountRequest() {
        WithdrawalEvent winningEvent = new WithdrawalEvent(
                UUID.randomUUID(),
                ACCOUNT_ID,
                AMOUNT,
                "SUCCESSFUL",
                Instant.now()
        );
        when(idempotencyRepository.find(ACCOUNT_ID, "retry-test"))
                .thenReturn(
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(new StoredWithdrawal(AMOUNT, winningEvent))
                );
        doThrow(new org.springframework.dao.DuplicateKeyException("duplicate key"))
                .when(idempotencyRepository).save(eq("retry-test"), any(WithdrawalEvent.class));

        WithdrawalEvent response = withdrawalService.withdraw(ACCOUNT_ID, AMOUNT, "retry-test");

        assertEquals(winningEvent, response);
        verify(idempotencyRepository, times(10)).save(eq("retry-test"), any(WithdrawalEvent.class));
        verify(accountRepository, never()).debitIfVersionMatches(ACCOUNT_ID, AMOUNT, 0L);
        verifyNoInteractions(outboxRepository);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableRetry
    static class RetryTestConfiguration {
        @Bean
        AccountRepository accountRepository() {
            return mock(AccountRepository.class);
        }

        @Bean
        OutboxRepository outboxRepository() {
            return mock(OutboxRepository.class);
        }

        @Bean
        WithdrawalIdempotencyRepository withdrawalIdempotencyRepository() {
            return mock(WithdrawalIdempotencyRepository.class);
        }

        @Bean
        WithdrawalTransactionService withdrawalTransactionService(
                AccountRepository accountRepository,
                OutboxRepository outboxRepository,
                WithdrawalIdempotencyRepository idempotencyRepository,
                MeterRegistry meterRegistry
        ) {
            return new WithdrawalTransactionService(
                    accountRepository, outboxRepository, idempotencyRepository, meterRegistry
            );
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
