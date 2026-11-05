package org.example.bankcashwithdrawalapp.rest;

import org.example.bankcashwithdrawalapp.event.WithdrawalEvent;
import org.example.bankcashwithdrawalapp.service.WithdrawalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BankAccountControllerTest {
    private static final long ACCOUNT_ID = 101L;
    private static final BigDecimal AMOUNT = new BigDecimal("25.50");

    private WithdrawalService withdrawalService;
    private BankAccountController controller;

    @BeforeEach
    void setUp() {
        withdrawalService = mock(WithdrawalService.class);
        controller = new BankAccountController(withdrawalService);
    }

    @Test
    void withdrawDelegatesRequestAndMapsSuccessfulResponse() {
        WithdrawalEvent event = new WithdrawalEvent(
                UUID.randomUUID(),
                ACCOUNT_ID,
                AMOUNT,
                "SUCCESSFUL",
                Instant.now()
        );
        when(withdrawalService.withdraw(ACCOUNT_ID, AMOUNT)).thenReturn(event);

        var response = controller.withdraw(ACCOUNT_ID, AMOUNT);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(ACCOUNT_ID, response.getBody().accountId());
        assertEquals(0, AMOUNT.compareTo(response.getBody().amount()));
        assertEquals("SUCCESSFUL", response.getBody().status());
        assertSame(event.eventId(), response.getBody().eventId());
        verify(withdrawalService).withdraw(ACCOUNT_ID, AMOUNT);
    }
}
