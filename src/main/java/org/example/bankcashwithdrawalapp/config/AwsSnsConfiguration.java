package org.example.bankcashwithdrawalapp.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;

@Configuration
@EnableScheduling
public class AwsSnsConfiguration {
    @Bean
    public SnsClient snsClient(@Value("${app.aws.region:af-south-1}") String region) {
        return SnsClient.builder()
                .region(Region.of(region))
                .build();
    }
}
