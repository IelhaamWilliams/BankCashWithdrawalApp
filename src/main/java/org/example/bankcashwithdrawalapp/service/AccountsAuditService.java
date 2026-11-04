package org.example.bankcashwithdrawalapp.service;

import jakarta.persistence.EntityManager;
import org.example.bankcashwithdrawalapp.entity.Accounts;
import org.hibernate.envers.AuditReader;
import org.hibernate.envers.AuditReaderFactory;
import org.hibernate.envers.DefaultRevisionEntity;
import org.hibernate.envers.RevisionType;
import org.hibernate.envers.query.AuditEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class AccountsAuditService {
    private final EntityManager entityManager;

    public AccountsAuditService(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public List<AccountsAuditEntry> getAuditHistory(Long accountId) {
        AuditReader auditReader = AuditReaderFactory.get(entityManager);
        List<?> revisions = auditReader.createQuery()
                .forRevisionsOfEntity(Accounts.class, false, true)
                .add(AuditEntity.id().eq(accountId))
                .addOrder(AuditEntity.revisionNumber().desc())
                .getResultList();

        List<AccountsAuditEntry> history = new ArrayList<>(revisions.size());
        for (Object revisionResult : revisions) {
            Object[] revision = (Object[]) revisionResult;
            Accounts account = (Accounts) revision[0];
            DefaultRevisionEntity revisionEntity = (DefaultRevisionEntity) revision[1];
            RevisionType revisionType = (RevisionType) revision[2];

            history.add(new AccountsAuditEntry(
                    revisionEntity.getId(),
                    Instant.ofEpochMilli(revisionEntity.getTimestamp()),
                    revisionType,
                    account.getId(),
                    account.getBalance(),
                    account.getVersion()
            ));
        }
        return List.copyOf(history);
    }

    public record AccountsAuditEntry(
            Integer revisionNumber,
            Instant revisionTimestamp,
            RevisionType revisionType,
            Long accountId,
            BigDecimal balance,
            Long version
    ) {
    }
}
