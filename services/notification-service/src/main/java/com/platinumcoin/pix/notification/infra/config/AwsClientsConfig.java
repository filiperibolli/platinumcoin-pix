package com.platinumcoin.pix.notification.infra.config;

import com.platinumcoin.pix.common.aws.LocalStackAwsOverride;
import com.platinumcoin.pix.common.event.ProcessedEventStore;
import com.platinumcoin.pix.common.metrics.AwsSdkDependencyMetrics;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
 *
 * <p><b>Neither client carries a credential or an endpoint of its own</b> (ADR-0013, swept in step 45):
 * the SDK's {@code DefaultCredentialsProvider} chain resolves the ambient role, and only
 * {@link LocalStackAwsConfig} — a {@code @Profile("local")} bean — redirects them at the emulator.
 */
@Configuration
@EnableConfigurationProperties(AwsProperties.class)
public class AwsClientsConfig {

    private static final Logger log = LoggerFactory.getLogger(AwsClientsConfig.class);

    @Bean
    SqsClient sqsClient(AwsProperties aws, AwsSdkDependencyMetrics dependencyMetrics,
            ObjectProvider<LocalStackAwsOverride> localStack) {
        var builder = SqsClient.builder()
                .region(Region.of(aws.region()))
                // Every call this client makes is timed into pix.dependency.seconds (step 72). Attached
                // per client because the SDK offers no global hook — an explicit line beats a publisher
                // that silently measures nothing.
                .overrideConfiguration(override -> override.addMetricPublisher(dependencyMetrics));
        // The local profile — and nothing else — points the client at the emulator (ADR-0013). Absent
        // it, no endpoint and no credentials are passed, so the SDK resolves the ambient role.
        localStack.ifAvailable(override -> override.applyTo(builder));
        log.info("Built the SQS client, credentials are never logged | endpoint={} region={} "
                        + "localStackOverride={}",
                aws.endpointUrl(), aws.region(), localStack.getIfAvailable() != null);
        return builder.build();
    }

    @Bean
    DynamoDbClient dynamoDbClient(AwsProperties aws, AwsSdkDependencyMetrics dependencyMetrics,
            ObjectProvider<LocalStackAwsOverride> localStack) {
        var builder = DynamoDbClient.builder()
                .region(Region.of(aws.region()))
                // Every call this client makes is timed into pix.dependency.seconds (step 72). Attached
                // per client because the SDK offers no global hook — an explicit line beats a publisher
                // that silently measures nothing.
                .overrideConfiguration(override -> override.addMetricPublisher(dependencyMetrics));
        // The local profile — and nothing else — points the client at the emulator (ADR-0013). Absent
        // it, no endpoint and no credentials are passed, so the SDK resolves the ambient role.
        localStack.ifAvailable(override -> override.applyToDynamoDb(builder));
        log.info("Built the DynamoDB client for the shared dedup table, credentials are never logged "
                        + "| endpoint={} region={} localStackOverride={}",
                aws.dynamoDbEndpointUrl(), aws.region(), localStack.getIfAvailable() != null);
        return builder.build();
    }

    /** The shared dedup gate (step 29), on the platform {@link Clock} so its TTL is testable. */
    @Bean
    ProcessedEventStore processedEventStore(DynamoDbClient dynamo, Clock clock) {
        return new ProcessedEventStore(dynamo, clock);
    }
}
