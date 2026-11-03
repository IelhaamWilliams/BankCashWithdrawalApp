package org.example.bankcashwithdrawalapp.rest;

import org.example.bankcashwithdrawalapp.service.WithdrawalService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@RestController
@RequestMapping("/bank")
public class BankAccountController {
    private final WithdrawalService withdrawalService;

    public BankAccountController(WithdrawalService withdrawalService) {
        this.withdrawalService = withdrawalService;
    }

    @PostMapping("/withdraw")
    public ResponseEntity<WithdrawalResponse> withdraw(
            @RequestParam("accountId") Long accountId,
            @RequestParam("amount") BigDecimal amount
    ) {
        return ResponseEntity.ok(WithdrawalResponse.from(withdrawalService.withdraw(accountId, amount)));
    }
}
