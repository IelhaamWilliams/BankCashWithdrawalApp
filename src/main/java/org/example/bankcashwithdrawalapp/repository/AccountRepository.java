package org.example.bankcashwithdrawalapp.repository;

import org.example.bankcashwithdrawalapp.entity.Accounts;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;

@Repository
public interface AccountRepository extends JpaRepository<Accounts, Long> {
    default boolean debitIfVersionMatches(Long accountId, BigDecimal amount, Long expectedVersion) {
        Accounts account = findById(accountId).orElse(null);
        if (account == null
                || !account.getVersion().equals(expectedVersion)
                || account.getBalance().compareTo(amount) < 0) {
            return false;
        }

        account.setBalance(account.getBalance().subtract(amount));
        saveAndFlush(account);
        return true;
    }
}
