package com.platinumcoin.pix.notification.infra.config;

import com.platinumcoin.pix.common.event.ProcessedEventStore;
import java.net.URI;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * The AWS composition root: SQS for {@code notification-queue}, and DynamoDB for one table only —
 * {@code pix_processed_events}, the shared consumer-dedup gate.
 *
 * <p>Worth noticing what is <b>absent</b>. This service reads no ledger, no transaction and no account:
 * everything it needs to route and display an event already travels in the event. That is the payoff of
 * putting {@code creditorAccountId} and {@code amountCents} in the payload upstream, and it is why a
 * directory or ledger outage cannot stop notifications.
 */
@Configuration
@EnableConfigurationProperties(AwsProperties.class)
public class AwsClientsConfig {

    private static final Logger log = LoggerFactory.getLogger(AwsClientsConfig.class);

    @Bean
    SqsClient sqsClient(AwsProperties aws) {
        log.info("Built the SQS client, credentials are never logged | endpoint={} region={}",
                aws.endpointUrl(), aws.region());
        return SqsClient.builder()
                .endpointOverride(URI.create(aws.endpointUrl()))
                .region(Region.of(aws.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(aws.accessKeyId(), aws.secretAccessKey())))
                .build();
    }

    @Bean
    DynamoDbClient dynamoDbClient(AwsProperties aws) {
        log.info("Built the DynamoDB client for the shared dedup table, credentials are never logged "
                + "| endpoint={} region={}", aws.dynamoDbEndpointUrl(), aws.region());
        return DynamoDbClient.builder()
                .endpointOverride(URI.create(aws.dynamoDbEndpointUrl()))
                .region(Region.of(aws.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(aws.accessKeyId(), aws.secretAccessKey())))
                .build();
    }

    /** The shared dedup gate (step 29), on the platform {@link Clock} so its TTL is testable. */
    @Bean
    ProcessedEventStore processedEventStore(DynamoDbClient dynamo, Clock clock) {
        return new ProcessedEventStore(dynamo, clock);
    }
}
