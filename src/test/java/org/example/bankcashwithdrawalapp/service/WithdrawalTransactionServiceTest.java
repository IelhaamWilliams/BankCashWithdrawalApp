package org.example.bankcashwithdrawalapp.service;

import org.example.bankcashwithdrawalapp.entity.Accounts;
import org.example.bankcashwithdrawalapp.event.WithdrawalEvent;
import org.example.bankcashwithdrawalapp.exception.AccountNotFoundException;
import org.example.bankcashwithdrawalapp.exception.ConcurrentWithdrawalException;
import org.example.bankcashwithdrawalapp.exception.InsufficientFundsException;
import org.example.bankcashwithdrawalapp.exception.InvalidWithdrawalException;
import org.example.bankcashwithdrawalapp.repository.AccountRepository;
import org.example.bankcashwithdrawalapp.repository.OutboxRepository;
import org.example.bankcashwithdrawalapp.repository.WithdrawalIdempotencyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WithdrawalTransactionServiceTest {
    private static final long ACCOUNT_ID = 101L;
    private static final String IDEMPOTENCY_KEY = "request-1";

    private AccountRepository accountRepository;
    private OutboxRepository outboxRepository;
    private WithdrawalIdempotencyRepository idempotencyRepository;
    private WithdrawalTransactionService withdrawalService;

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        outboxRepository = mock(OutboxRepository.class);
        idempotencyRepository = mock(WithdrawalIdempotencyRepository.class);
        withdrawalService = new WithdrawalTransactionService(
                accountRepository, outboxRepository, idempotencyRepository
        );
    }

    @Test
    void withdrawDebitsAndEnqueuesSuccessfulEvent() {
        when(accountRepository.findById(ACCOUNT_ID))
                .thenReturn(Optional.of(new Accounts(ACCOUNT_ID, new BigDecimal("100.00"))));
        when(accountRepository.debitIfVersionMatches(ACCOUNT_ID, new BigDecimal("25.50"), 0L))
                .thenReturn(true);

        WithdrawalEvent response = withdrawalService.withdraw(
                ACCOUNT_ID, new BigDecimal("25.50"), IDEMPOTENCY_KEY
        );

        assertEquals(ACCOUNT_ID, response.accountId());
        assertEquals(0, new BigDecimal("25.50").compareTo(response.amount()));
        assertEquals("SUCCESSFUL", response.status());
        assertNotNull(response.eventId());
        assertNotNull(response.occurredAt());
        verify(accountRepository).debitIfVersionMatches(ACCOUNT_ID, new BigDecimal("25.50"), 0L);
        verify(idempotencyRepository).save(IDEMPOTENCY_KEY, response);

        ArgumentCaptor<WithdrawalEvent> eventCaptor = ArgumentCaptor.forClass(WithdrawalEvent.class);
        verify(outboxRepository).enqueue(eventCaptor.capture());
        assertEquals(response, eventCaptor.getValue());
    }

    @Test
    void withdrawRejectsUnknownAccountWithoutEnqueueingEvent() {
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThrows(
                AccountNotFoundException.class,
                () -> withdrawalService.withdraw(ACCOUNT_ID, new BigDecimal("10.00"), IDEMPOTENCY_KEY)
        );

        verify(accountRepository, never()).debitIfVersionMatches(any(), any(), any());
        verifyNoInteractions(outboxRepository);
    }

    @Test
    void withdrawRejectsInsufficientBalanceWithoutDebiting() {
        when(accountRepository.findById(ACCOUNT_ID))
                .thenReturn(Optional.of(new Accounts(ACCOUNT_ID, new BigDecimal("5.00"))));

        assertThrows(
                InsufficientFundsException.class,
                () -> withdrawalService.withdraw(ACCOUNT_ID, new BigDecimal("10.00"), IDEMPOTENCY_KEY)
        );

        verify(accountRepository, never()).debitIfVersionMatches(any(), any(), any());
        verifyNoInteractions(outboxRepository);
    }

    @Test
    void withdrawReportsOptimisticLockConflictWithoutEnqueueingEvent() {
        when(accountRepository.findById(ACCOUNT_ID))
                .thenReturn(Optional.of(new Accounts(ACCOUNT_ID, new BigDecimal("100.00"))));
        when(accountRepository.debitIfVersionMatches(ACCOUNT_ID, new BigDecimal("10.00"), 0L))
                .thenReturn(false);

        assertThrows(
                ConcurrentWithdrawalException.class,
                () -> withdrawalService.withdraw(ACCOUNT_ID, new BigDecimal("10.00"), IDEMPOTENCY_KEY)
        );

        verifyNoInteractions(outboxRepository);
    }

    @Test
    void withdrawRejectsInvalidAmountsBeforeAccessingRepositories() {
        assertThrows(
                InvalidWithdrawalException.class,
                () -> withdrawalService.withdraw(ACCOUNT_ID, BigDecimal.ZERO, IDEMPOTENCY_KEY)
        );
        assertThrows(
                InvalidWithdrawalException.class,
                () -> withdrawalService.withdraw(ACCOUNT_ID, new BigDecimal("1.001"), IDEMPOTENCY_KEY)
        );

        verifyNoInteractions(accountRepository, outboxRepository, idempotencyRepository);
    }
}
