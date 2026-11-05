package org.example.bankcashwithdrawalapp.service;

import org.example.bankcashwithdrawalapp.entity.Accounts;
import org.example.bankcashwithdrawalapp.event.WithdrawalEvent;
import org.example.bankcashwithdrawalapp.exception.AccountNotFoundException;
import org.example.bankcashwithdrawalapp.exception.ConcurrentWithdrawalException;
import org.example.bankcashwithdrawalapp.exception.IdempotencyKeyReuseException;
import org.example.bankcashwithdrawalapp.exception.InsufficientFundsException;
import org.example.bankcashwithdrawalapp.exception.InvalidWithdrawalException;
import org.example.bankcashwithdrawalapp.repository.AccountRepository;
import org.example.bankcashwithdrawalapp.repository.OutboxRepository;
import org.example.bankcashwithdrawalapp.repository.WithdrawalIdempotencyRepository;
import org.example.bankcashwithdrawalapp.repository.WithdrawalIdempotencyRepository.StoredWithdrawal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
public class WithdrawalTransactionService {
    private static final Logger LOGGER = LoggerFactory.getLogger(WithdrawalTransactionService.class);
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;

    private final AccountRepository accountRepository;
    private final OutboxRepository outboxRepository;
    private final WithdrawalIdempotencyRepository idempotencyRepository;

    public WithdrawalTransactionService(
            AccountRepository accountRepository,
            OutboxRepository outboxRepository,
            WithdrawalIdempotencyRepository idempotencyRepository
    ) {
        this.accountRepository = accountRepository;
        this.outboxRepository = outboxRepository;
        this.idempotencyRepository = idempotencyRepository;
    }

    @Transactional
    @Retryable(
            retryFor = {
                    ConcurrentWithdrawalException.class,
                    OptimisticLockingFailureException.class,
                    DuplicateKeyException.class
            },
            noRetryFor = {
                    AccountNotFoundException.class,
                    IdempotencyKeyReuseException.class,
                    InsufficientFundsException.class,
                    InvalidWithdrawalException.class
            },
            notRecoverable = {
                    AccountNotFoundException.class,
                    IdempotencyKeyReuseException.class,
                    InsufficientFundsException.class,
                    InvalidWithdrawalException.class
            },
            maxAttempts = 10,
            backoff = @Backoff(delay = 20, maxDelay = 1000, multiplier = 2, random = true)
    )
    public WithdrawalEvent withdraw(Long accountId, BigDecimal amount, String idempotencyKey) {
        amount = validateAndNormalize(accountId, amount, idempotencyKey);

        var existing = idempotencyRepository.find(accountId, idempotencyKey);
        if (existing.isPresent()) {
            StoredWithdrawal storedWithdrawal = existing.get();
            if (storedWithdrawal.amount().compareTo(amount) != 0) {
                throw new IdempotencyKeyReuseException();
            }
            return storedWithdrawal.event();
        }

        var account = accountRepository.findById(accountId);
        if (account.isEmpty()) {
            throw new AccountNotFoundException(accountId);
        }
        Accounts snapshot = account.get();
        if (snapshot.getBalance().compareTo(amount) < 0) {
            throw new InsufficientFundsException(accountId);
        }

        WithdrawalEvent event = new WithdrawalEvent(
                UUID.randomUUID(),
                accountId,
                amount,
                "SUCCESSFUL",
                Instant.now().truncatedTo(ChronoUnit.MICROS)
        );
        idempotencyRepository.save(idempotencyKey, event);

        if (!accountRepository.debitIfVersionMatches(accountId, amount, snapshot.getVersion())) {
            throw new ConcurrentWithdrawalException(accountId);
        }

        outboxRepository.enqueue(event);
        return event;
    }

    @Recover
    public WithdrawalEvent recoverConcurrentWithdrawal(
            OptimisticLockingFailureException exception,
            Long accountId,
            BigDecimal amount,
            String idempotencyKey
    ) {
        LOGGER.error("Withdrawal failed after all retry attempts for account {}, reason: {}",
                accountId, exception.getMessage());
        throw new ConcurrentWithdrawalException(accountId);
    }

    @Recover
    public WithdrawalEvent recoverConcurrentWithdrawal(
            ConcurrentWithdrawalException exception,
            Long accountId,
            BigDecimal amount,
            String idempotencyKey
    ) {
        LOGGER.error("Withdrawal failed after all retry attempts for account {}, reason: {}",
                accountId, exception.getMessage());
        throw exception;
    }

    @Recover
    public WithdrawalEvent recoverDuplicateKey(
            DuplicateKeyException exception,
            Long accountId,
            BigDecimal amount,
            String idempotencyKey
    ) {
        LOGGER.error("Unable to resolve concurrent idempotency-key request for account {}", accountId, exception);
        throw new IdempotencyKeyReuseException();
    }

    private BigDecimal validateAndNormalize(Long accountId, BigDecimal amount, String idempotencyKey) {
        if (accountId == null || accountId <= 0) {
            throw new InvalidWithdrawalException("Account ID must be positive");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()
                || idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new InvalidWithdrawalException("Idempotency key must be between 1 and 128 characters");
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
