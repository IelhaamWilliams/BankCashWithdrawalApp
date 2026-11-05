package org.example.bankcashwithdrawalapp;

import org.example.bankcashwithdrawalapp.exception.InvalidWithdrawalException;
import org.example.bankcashwithdrawalapp.exception.InsufficientFundsException;
import org.example.bankcashwithdrawalapp.entity.Accounts;
import org.example.bankcashwithdrawalapp.messaging.WithdrawalEventPublisher;
import org.example.bankcashwithdrawalapp.repository.AccountRepository;
import org.example.bankcashwithdrawalapp.repository.OutboxRepository;
import org.example.bankcashwithdrawalapp.rest.ApiExceptionHandler;
import org.example.bankcashwithdrawalapp.rest.BankAccountController;
import org.example.bankcashwithdrawalapp.service.AccountsAuditService;
import org.example.bankcashwithdrawalapp.service.OutboxDispatcher;
import org.example.bankcashwithdrawalapp.service.WithdrawalTransactionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "app.outbox.enabled=false")
class WithdrawalFlowIntegrationTests {
    private static final long ACCOUNT_ID = 101L;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private WithdrawalTransactionService withdrawalService;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AccountsAuditService accountsAuditService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM withdrawal_idempotency");
        jdbcTemplate.update("DELETE FROM withdrawal_outbox");
        jdbcTemplate.update("DELETE FROM accounts_AUD");
        jdbcTemplate.update("DELETE FROM REVINFO");
        jdbcTemplate.update("DELETE FROM accounts");
        jdbcTemplate.update("INSERT INTO accounts (id, balance) VALUES (?, ?)", ACCOUNT_ID, "100.00");
        mockMvc = MockMvcBuilders.standaloneSetup(new BankAccountController(withdrawalService))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void withdrawDebitsBalanceAndEnqueuesEvent() throws Exception {
        mockMvc.perform(post("/bank/withdraw")
                        .header("Idempotency-Key", "integration-basic")
                        .param("accountId", Long.toString(ACCOUNT_ID))
                        .param("amount", "25.50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId", not(emptyString())))
                .andExpect(jsonPath("$.accountId").value(ACCOUNT_ID))
                .andExpect(jsonPath("$.amount").value(25.50))
                .andExpect(jsonPath("$.status").value("SUCCESSFUL"));

        assertEquals(0, new BigDecimal("74.50").compareTo(
                jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", BigDecimal.class, ACCOUNT_ID)
        ));
        assertEquals(1, countOutboxEvents());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM accounts_AUD WHERE id = ? AND balance = ? AND REVTYPE = 1",
                Integer.class,
                ACCOUNT_ID,
                new BigDecimal("74.50")
        ));
    }

    @Test
    void repeatedRequestWithSameKeyReturnsOriginalEventWithoutSecondDebit() throws Exception {
        String firstResponse = mockMvc.perform(post("/bank/withdraw")
                        .header("Idempotency-Key", "replay-key")
                        .param("accountId", Long.toString(ACCOUNT_ID))
                        .param("amount", "25.50"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(post("/bank/withdraw")
                        .header("Idempotency-Key", "replay-key")
                        .param("accountId", Long.toString(ACCOUNT_ID))
                        .param("amount", "25.50"))
                .andExpect(status().isOk())
                .andExpect(result -> assertEquals(
                        firstResponse, result.getResponse().getContentAsString()
                ));

        assertEquals(0, new BigDecimal("74.50").compareTo(
                jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", BigDecimal.class, ACCOUNT_ID)
        ));
        assertEquals(1, countOutboxEvents());
    }

    @Test
    void reusingKeyWithDifferentAmountReturnsConflictWithoutSecondDebit() throws Exception {
        mockMvc.perform(post("/bank/withdraw")
                        .header("Idempotency-Key", "conflict-key")
                        .param("accountId", Long.toString(ACCOUNT_ID))
                        .param("amount", "25.50"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/bank/withdraw")
                        .header("Idempotency-Key", "conflict-key")
                        .param("accountId", Long.toString(ACCOUNT_ID))
                        .param("amount", "10.00"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        assertEquals(0, new BigDecimal("74.50").compareTo(
                jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", BigDecimal.class, ACCOUNT_ID)
        ));
        assertEquals(1, countOutboxEvents());
    }

    @Test
    void auditServiceReturnsAccountRevisionsNewestFirst() {
        withdrawalService.withdraw(ACCOUNT_ID, new BigDecimal("25.50"), "audit-request");

        List<AccountsAuditService.AccountsAuditEntry> history =
                accountsAuditService.getAuditHistory(ACCOUNT_ID);

        assertEquals(1, history.size());
        assertEquals(ACCOUNT_ID, history.getFirst().accountId());
        assertEquals(0, new BigDecimal("74.50").compareTo(history.getFirst().balance()));
        assertNotNull(history.getFirst().revisionNumber());
        assertNotNull(history.getFirst().revisionTimestamp());
        assertEquals(org.hibernate.envers.RevisionType.MOD, history.getFirst().revisionType());
    }

    @Test
    void auditServiceReturnsEmptyHistoryForAccountWithoutAuditedChanges() {
        assertTrue(accountsAuditService.getAuditHistory(ACCOUNT_ID).isEmpty());
    }

    @Test
    void insufficientFundsDoesNotDebitOrEnqueueEvent() throws Exception {
        mockMvc.perform(post("/bank/withdraw")
                        .header("Idempotency-Key", "insufficient-key")
                        .param("accountId", Long.toString(ACCOUNT_ID))
                        .param("amount", "101.00"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));

        assertEquals(0, new BigDecimal("100.00").compareTo(
                jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", BigDecimal.class, ACCOUNT_ID)
        ));
        assertEquals(0, countOutboxEvents());
    }

    @Test
    void missingAccountReturnsNotFound() throws Exception {
        mockMvc.perform(post("/bank/withdraw")
                        .header("Idempotency-Key", "missing-account-key")
                        .param("accountId", "404")
                        .param("amount", "10.00"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));

        assertEquals(0, countOutboxEvents());
    }

    @Test
    void nonPositiveAmountReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/bank/withdraw")
                        .header("Idempotency-Key", "zero-amount-key")
                        .param("accountId", Long.toString(ACCOUNT_ID))
                        .param("amount", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_WITHDRAWAL"));

        assertEquals(0, countOutboxEvents());
    }

    @Test
    void amountWithFractionsSmallerThanOneCentReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/bank/withdraw")
                        .header("Idempotency-Key", "fractional-amount-key")
                        .param("accountId", Long.toString(ACCOUNT_ID))
                        .param("amount", "1.001"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_WITHDRAWAL"));

        assertEquals(0, new BigDecimal("100.00").compareTo(
                jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", BigDecimal.class, ACCOUNT_ID)
        ));
        assertEquals(0, countOutboxEvents());
    }

    @Test
    void malformedOrMissingParametersReturnConsistentBadRequest() throws Exception {
        mockMvc.perform(post("/bank/withdraw")
                        .header("Idempotency-Key", "malformed-params-key")
                        .param("accountId", "not-a-number")
                        .param("amount", "10.00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mockMvc.perform(post("/bank/withdraw")
                        .header("Idempotency-Key", "missing-param-key")
                        .param("accountId", Long.toString(ACCOUNT_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void missingIdempotencyKeyReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/bank/withdraw")
                        .param("accountId", Long.toString(ACCOUNT_ID))
                        .param("amount", "10.00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void dispatcherPublishesAndMarksClaimedEvent() {
        withdrawalService.withdraw(ACCOUNT_ID, new BigDecimal("10.00"), "dispatcher-publish-request");
        AtomicReference<String> publishedPayload = new AtomicReference<>();
        WithdrawalEventPublisher publisher = (eventId, payload) -> publishedPayload.set(payload);
        OutboxDispatcher dispatcher = new OutboxDispatcher(outboxRepository, publisher, 10, 60, 1000);

        dispatcher.dispatchPendingEvents();

        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM withdrawal_outbox WHERE status = 'PUBLISHED'",
                Integer.class
        ));
        assertNotNull(publishedPayload.get());
        assertTrue(publishedPayload.get().contains("\"accountId\":" + ACCOUNT_ID));
    }

    @Test
    void dispatcherSchedulesRetryAfterPublishFailure() {
        withdrawalService.withdraw(ACCOUNT_ID, new BigDecimal("10.00"), "dispatcher-retry-request");
        WithdrawalEventPublisher publisher = (eventId, payload) -> {
            throw new IllegalStateException("SNS unavailable");
        };
        OutboxDispatcher dispatcher = new OutboxDispatcher(outboxRepository, publisher, 10, 60, 1000);

        dispatcher.dispatchPendingEvents();

        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM withdrawal_outbox WHERE status = 'PENDING' AND attempt_count = 1",
                Integer.class
        ));
        assertEquals("SNS unavailable", jdbcTemplate.queryForObject(
                "SELECT last_error FROM withdrawal_outbox",
                String.class
        ));
    }

    @Test
    void invalidWithdrawalDoesNotChangeBalanceOrCreateEvent() {
        assertThrows(InvalidWithdrawalException.class,
                () -> withdrawalService.withdraw(ACCOUNT_ID, new BigDecimal("-1.00"), "invalid-request"));
    }

    @Test
    @Transactional
    void staleAccountVersionCannotDebitBalance() {
        Accounts snapshot = accountRepository.findById(ACCOUNT_ID).orElseThrow();
        jdbcTemplate.update(
                "UPDATE accounts SET balance = balance - ?, version = version + 1 WHERE id = ?",
                new BigDecimal("10.00"),
                ACCOUNT_ID
        );

        assertThrows(OptimisticLockingFailureException.class,
                () -> accountRepository.debitIfVersionMatches(
                        ACCOUNT_ID,
                        new BigDecimal("20.00"),
                        snapshot.getVersion()
                ));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT version FROM accounts WHERE id = ?",
                Integer.class,
                ACCOUNT_ID
        ));
        assertEquals(0, new BigDecimal("90.00").compareTo(
                jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", BigDecimal.class, ACCOUNT_ID)
        ));
    }

    @Test
    void concurrentWithdrawalsRetryOptimisticConflictsWithoutOverdrawing() throws Exception {
        int requestCount = 8;
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < requestCount; i++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to start concurrent withdrawals");
                    }
                    try {
                        withdrawalService.withdraw(
                                ACCOUNT_ID,
                                new BigDecimal("15.00"),
                                "concurrent-withdrawal-" + Thread.currentThread().getId()
                        );
                        return true;
                    } catch (InsufficientFundsException exception) {
                        return false;
                    }
                }));
            }

            assertTrue(ready.await(10, TimeUnit.SECONDS), "Withdrawal workers did not become ready");
            start.countDown();

            int successfulWithdrawals = 0;
            for (Future<Boolean> result : results) {
                if (result.get(30, TimeUnit.SECONDS)) {
                    successfulWithdrawals++;
                }
            }

            assertEquals(6, successfulWithdrawals);
            assertEquals(0, new BigDecimal("10.00").compareTo(
                    jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", BigDecimal.class, ACCOUNT_ID)
            ));
            assertEquals(6, jdbcTemplate.queryForObject(
                    "SELECT version FROM accounts WHERE id = ?",
                    Integer.class,
                    ACCOUNT_ID
            ));
            assertEquals(6, countOutboxEvents());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentRequestsWithSameKeyDebitAndEnqueueOnlyOnce() throws Exception {
        int requestCount = 8;
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        try {
            List<Future<org.example.bankcashwithdrawalapp.event.WithdrawalEvent>> results = new ArrayList<>();
            for (int i = 0; i < requestCount; i++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to start concurrent withdrawals");
                    }
                    return withdrawalService.withdraw(
                            ACCOUNT_ID, new BigDecimal("15.00"), "concurrent-same-key"
                    );
                }));
            }

            assertTrue(ready.await(10, TimeUnit.SECONDS), "Withdrawal workers did not become ready");
            start.countDown();

            var originalEvent = results.getFirst().get(30, TimeUnit.SECONDS);
            for (Future<org.example.bankcashwithdrawalapp.event.WithdrawalEvent> result : results) {
                assertEquals(originalEvent, result.get(30, TimeUnit.SECONDS));
            }

            assertEquals(0, new BigDecimal("85.00").compareTo(
                    jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", BigDecimal.class, ACCOUNT_ID)
            ));
            assertEquals(1, countOutboxEvents());
        } finally {
            executor.shutdownNow();
        }
    }

    private int countOutboxEvents() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM withdrawal_outbox", Integer.class);
    }
}
