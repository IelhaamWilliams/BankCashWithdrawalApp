package org.example.bankcashwithdrawalapp;

import org.example.bankcashwithdrawalapp.exception.InvalidWithdrawalException;
import org.example.bankcashwithdrawalapp.messaging.WithdrawalEventPublisher;
import org.example.bankcashwithdrawalapp.repository.OutboxRepository;
import org.example.bankcashwithdrawalapp.rest.ApiExceptionHandler;
import org.example.bankcashwithdrawalapp.rest.BankAccountController;
import org.example.bankcashwithdrawalapp.service.OutboxDispatcher;
import org.example.bankcashwithdrawalapp.service.WithdrawalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
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
    private WithdrawalService withdrawalService;

    @Autowired
    private OutboxRepository outboxRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM withdrawal_outbox");
        jdbcTemplate.update("DELETE FROM accounts");
        jdbcTemplate.update("INSERT INTO accounts (id, balance) VALUES (?, ?)", ACCOUNT_ID, "100.00");
        mockMvc = MockMvcBuilders.standaloneSetup(new BankAccountController(withdrawalService))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void withdrawDebitsBalanceAndEnqueuesEvent() throws Exception {
        mockMvc.perform(post("/bank/withdraw")
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
    }

    @Test
    void insufficientFundsDoesNotDebitOrEnqueueEvent() throws Exception {
        mockMvc.perform(post("/bank/withdraw")
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
                        .param("accountId", "404")
                        .param("amount", "10.00"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));

        assertEquals(0, countOutboxEvents());
    }

    @Test
    void nonPositiveAmountReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/bank/withdraw")
                        .param("accountId", Long.toString(ACCOUNT_ID))
                        .param("amount", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_WITHDRAWAL"));

        assertEquals(0, countOutboxEvents());
    }

    @Test
    void amountWithFractionsSmallerThanOneCentReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/bank/withdraw")
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
                        .param("accountId", "not-a-number")
                        .param("amount", "10.00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mockMvc.perform(post("/bank/withdraw")
                        .param("accountId", Long.toString(ACCOUNT_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void dispatcherPublishesAndMarksClaimedEvent() {
        withdrawalService.withdraw(ACCOUNT_ID, new BigDecimal("10.00"));
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
        withdrawalService.withdraw(ACCOUNT_ID, new BigDecimal("10.00"));
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
                () -> withdrawalService.withdraw(ACCOUNT_ID, new BigDecimal("-1.00")));
    }

    private int countOutboxEvents() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM withdrawal_outbox", Integer.class);
    }
}
