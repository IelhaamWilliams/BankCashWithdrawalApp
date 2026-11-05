package org.example.bankcashwithdrawalapp.rest;

import org.example.bankcashwithdrawalapp.service.WithdrawalTransactionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@RestController
@RequestMapping("/bank")
public class BankAccountController {
    private final WithdrawalTransactionService withdrawalService;

    public BankAccountController(WithdrawalTransactionService withdrawalService) {
        this.withdrawalService = withdrawalService;
    }

    @PostMapping("/withdraw")
    public ResponseEntity<WithdrawalResponse> withdraw(
            @RequestParam("accountId") Long accountId,
            @RequestParam("amount") BigDecimal amount,
            @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return ResponseEntity.ok(WithdrawalResponse.from(
                withdrawalService.withdraw(accountId, amount, idempotencyKey)
        ));
    }
}
