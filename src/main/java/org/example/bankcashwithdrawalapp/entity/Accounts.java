package org.example.bankcashwithdrawalapp.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.envers.Audited;

import java.math.BigDecimal;

@Entity
@Table(name = "accounts")
@Audited
public class Accounts {
    @Id
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "balance", nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected Accounts() {
    }

    public Accounts(Long id, BigDecimal balance) {
        this.id = id;
        this.balance = balance;
        this.version = 0L;
    }

    public Long getId() {
        return id;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public void setBalance(BigDecimal balance) {
        this.balance = balance;
    }

    public Long getVersion() {
        return version;
    }
}
