package org.example.bankcashwithdrawalapp.service;

import org.example.bankcashwithdrawalapp.event.WithdrawalEvent;
import org.example.bankcashwithdrawalapp.exception.AccountNotFoundException;
import org.example.bankcashwithdrawalapp.exception.ConcurrentWithdrawalException;
import org.example.bankcashwithdrawalapp.exception.InsufficientFundsException;
import org.example.bankcashwithdrawalapp.exception.InvalidWithdrawalException;
import org.example.bankcashwithdrawalapp.entity.Accounts;
import org.example.bankcashwithdrawalapp.repository.AccountRepository;
import org.example.bankcashwithdrawalapp.repository.OutboxRepository;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

@Service
public class WithdrawalService {
    private final AccountRepository accountRepository;
    private final OutboxRepository outboxRepository;

    public WithdrawalService(AccountRepository accountRepository, OutboxRepository outboxRepository) {
        this.accountRepository = accountRepository;
        this.outboxRepository = outboxRepository;
    }

    @Transactional
    @Retryable(
            retryFor = {ConcurrentWithdrawalException.class, OptimisticLockingFailureException.class},
            noRetryFor = {
                    AccountNotFoundException.class,
                    InsufficientFundsException.class,
                    InvalidWithdrawalException.class
            },
            notRecoverable = {
                    AccountNotFoundException.class,
                    InsufficientFundsException.class,
                    InvalidWithdrawalException.class
            },
            maxAttempts = 5,
            backoff = @Backoff(delay = 10, maxDelay = 200, multiplier = 2)
    )
    public WithdrawalEvent withdraw(Long accountId, BigDecimal amount) {
        amount = validateAndNormalize(accountId, amount);

        var account = accountRepository.findById(accountId);
        if (account.isEmpty()) {
            throw new AccountNotFoundException(accountId);
        }
        Accounts snapshot = account.get();
        if (snapshot.getBalance().compareTo(amount) < 0) {
            throw new InsufficientFundsException(accountId);
        }
        if (!accountRepository.debitIfVersionMatches(accountId, amount, snapshot.getVersion())) {
            throw new ConcurrentWithdrawalException(accountId);
        }

        WithdrawalEvent event = new WithdrawalEvent(
                UUID.randomUUID(),
                accountId,
                amount,
                "SUCCESSFUL",
                Instant.now()
        );
        outboxRepository.enqueue(event);
        return event;
    }

    @Recover
    public WithdrawalEvent recoverOptimisticLock(
            OptimisticLockingFailureException exception,
            Long accountId,
            BigDecimal amount
    ) {
        throw new ConcurrentWithdrawalException(accountId);
    }

    private BigDecimal validateAndNormalize(Long accountId, BigDecimal amount) {
        if (accountId == null || accountId <= 0) {
            throw new InvalidWithdrawalException("Account ID must be positive");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new InvalidWithdrawalException("Withdrawal amount must be positive");
        }
        try {
            BigDecimal normalizedAmount = amount.setScale(2, RoundingMode.UNNECESSARY);
            if (normalizedAmount.precision() > 19) {
                throw new InvalidWithdrawalException("Withdrawal amount exceeds the supported maximum");
            }
            return normalizedAmount;
        } catch (ArithmeticException exception) {
            throw new InvalidWithdrawalException("Withdrawal amount must not have fractions smaller than one cent");
        }
    }
}
