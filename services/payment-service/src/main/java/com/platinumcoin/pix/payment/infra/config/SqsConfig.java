package com.platinumcoin.pix.payment.infra.config;

import com.platinumcoin.pix.common.aws.LocalStackAwsOverride;
import com.platinumcoin.pix.common.event.ProcessedEventStore;
import com.platinumcoin.pix.common.metrics.AwsSdkDependencyMetrics;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * The SQS half of payment-service's AWS composition root, added with the cold statement export
 * (step 53) — this service's <b>first</b> queue consumer.
 *
 * <h2>What changed about this service, and why it is worth noticing</h2>
 * Until this step payment-service published to SNS and consumed nothing: the fan-out was SNS's job and
 * its IAM policy was {@code sns:Publish} on one topic and no SQS permission at all. The export worker
 * makes it a consumer too ({@code statement-export-queue}), which is a genuine widening of its blast
 * radius, so it comes with the corresponding widening of
 * {@code infra/iam/payment-service-policy.json} — receive/delete/change-visibility on that one queue and
 * its DLQ, and nothing else. It publishes to no queue and reads no other service's.
 *
 * <p>{@link ProcessedEventStore} lands here for the same reason: a consumer needs the shared dedup gate
 * (Domain Safety Rule #2), and {@code pix_processed_events} is ADR-0006's deliberate one-table
 * exception. It reuses the {@link DynamoDbClient} the transaction store already builds — the table is a
 * different table, not a different store.
 *
 * <p><b>The client carries no credential and no endpoint of its own</b> (ADR-0013): only
 * {@link LocalStackAwsConfig} — a {@code @Profile("local")} bean — redirects it at the emulator.
 */
@Configuration
public class SqsConfig {

    private static final Logger log = LoggerFactory.getLogger(SqsConfig.class);

    @Bean
    SqsClient sqsClient(AwsProperties aws, AwsSdkDependencyMetrics dependencyMetrics,
            ObjectProvider<LocalStackAwsOverride> localStack) {
        var builder = SqsClient.builder()
                .region(Region.of(aws.region()))
                // Every call this client makes is timed into pix.dependency.seconds (step 72).
                .overrideConfiguration(override -> override.addMetricPublisher(dependencyMetrics));
        localStack.ifAvailable(override -> override.applyTo(builder));
        log.info("Built the SQS client for the statement-export worker, credentials are never logged | "
                        + "endpoint={} region={} localStackOverride={}",
                aws.endpointUrl(), aws.region(), localStack.getIfAvailable() != null);
        return builder.build();
    }

    /** The shared dedup gate (step 29), on the platform {@link Clock} so its TTL is testable. */
    @Bean
    ProcessedEventStore processedEventStore(DynamoDbClient dynamo, Clock clock) {
        return new ProcessedEventStore(dynamo, clock);
    }
}
