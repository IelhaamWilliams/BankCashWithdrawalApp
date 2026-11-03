package org.example.bankcashwithdrawalapp.messaging;

public interface WithdrawalEventPublisher {
    void publish(String eventId, String payload);
}
