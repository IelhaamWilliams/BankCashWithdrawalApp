package org.example.bankcashwithdrawalapp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "app.outbox.enabled=false")
class BankCashWithdrawalAppApplicationTests {

    @Test
    void contextLoads() {
    }

}
