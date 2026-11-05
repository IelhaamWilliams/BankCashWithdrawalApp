package org.example.bankcashwithdrawalapp.service;

import org.example.bankcashwithdrawalapp.entity.Accounts;
import org.example.bankcashwithdrawalapp.exception.ConcurrentWithdrawalException;
import org.example.bankcashwithdrawalapp.repository.AccountRepository;
import org.example.bankcashwithdrawalapp.repository.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
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
    private WithdrawalService withdrawalService;

    @BeforeEach
    void setUp() {
        reset(accountRepository, outboxRepository);
        when(accountRepository.findById(ACCOUNT_ID))
                .thenReturn(Optional.of(new Accounts(ACCOUNT_ID, new BigDecimal("100.00"))));
        when(accountRepository.debitIfVersionMatches(ACCOUNT_ID, AMOUNT, 0L))
                .thenReturn(false);
    }

    @Test
    void exhaustedConcurrentWithdrawalRetriesReturnConflictException() {
        assertThrows(
                ConcurrentWithdrawalException.class,
                () -> withdrawalService.withdraw(ACCOUNT_ID, AMOUNT)
        );

        verify(accountRepository, times(5)).findById(ACCOUNT_ID);
        verify(accountRepository, times(5)).debitIfVersionMatches(ACCOUNT_ID, AMOUNT, 0L);
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
        WithdrawalService withdrawalService(
                AccountRepository accountRepository,
                OutboxRepository outboxRepository
        ) {
            return new WithdrawalService(accountRepository, outboxRepository);
        }
    }
}
