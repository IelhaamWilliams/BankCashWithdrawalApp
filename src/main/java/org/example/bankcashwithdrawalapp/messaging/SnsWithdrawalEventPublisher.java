package org.example.bankcashwithdrawalapp.messaging;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.PublishRequest;

import java.util.Map;

@Component
public class SnsWithdrawalEventPublisher implements WithdrawalEventPublisher {
    private final SnsClient snsClient;
    private final String topicArn;

    public SnsWithdrawalEventPublisher(
            SnsClient snsClient,
            @Value("${app.aws.sns.topic-arn:}") String topicArn
    ) {
        this.snsClient = snsClient;
        this.topicArn = topicArn;
    }

    @Override
    public void publish(String eventId, String payload) {
        if (topicArn.isBlank()) {
            throw new IllegalStateException("AWS SNS topic ARN is not configured");
        }

        snsClient.publish(PublishRequest.builder()
                .topicArn(topicArn)
                .message(payload)
                .messageAttributes(Map.of(
                        "eventId",
                        MessageAttributeValue.builder()
                                .dataType("String")
                                .stringValue(eventId)
                                .build()
                ))
                .build());
    }
}
