package org.example.bankcashwithdrawalapp.service;

import org.example.bankcashwithdrawalapp.event.WithdrawalEvent;
import org.example.bankcashwithdrawalapp.exception.AccountNotFoundException;
import org.example.bankcashwithdrawalapp.exception.InsufficientFundsException;
import org.example.bankcashwithdrawalapp.exception.InvalidWithdrawalException;
import org.example.bankcashwithdrawalapp.repository.AccountRepository;
import org.example.bankcashwithdrawalapp.repository.OutboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    public WithdrawalEvent withdraw(Long accountId, BigDecimal amount) {
        amount = validateAndNormalize(accountId, amount);

        if (accountRepository.debitIfSufficient(accountId, amount) != 1) {
            if (!accountRepository.existsById(accountId)) {
                throw new AccountNotFoundException(accountId);
            }
            throw new InsufficientFundsException(accountId);
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
