package com.platinumcoin.pix.settlement.infra.config;

import com.platinumcoin.pix.common.aws.LocalStackAwsOverride;
import com.platinumcoin.pix.common.event.ProcessedEventStore;
import com.platinumcoin.pix.common.metrics.AwsSdkDependencyMetrics;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * The AWS composition root: the three long-lived, thread-safe clients this service needs — DynamoDB (the
 * guarded transitions, the outbox item, the dedup table), SQS (the settlement and audit queues) and S3
 * (the immutable audit trail, step 43) — plus the shared {@link ProcessedEventStore} they back. Kept
 * in {@code infra/} so no AWS type ever leaks toward the domain (ADR-0010).
 *
 * <p>The queue URL itself is resolved by the consumer that uses it (see
 * {@code SettlementQueueConsumer}), so nothing has to travel between {@code api/} and {@code infra/} —
 * the dependency arrows stay {@code api → domain ← infra} (ADR-0010).
 *
 * <p><b>No client here carries a credential or an endpoint of its own</b> (ADR-0013, swept in step 45):
 * the SDK's {@code DefaultCredentialsProvider} chain resolves the ambient role, and only
 * {@link LocalStackAwsConfig} — a {@code @Profile("local")} bean — redirects them at the emulator.
 */
@Configuration
@EnableConfigurationProperties(AwsProperties.class)
public class AwsClientsConfig {

    private static final Logger log = LoggerFactory.getLogger(AwsClientsConfig.class);

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
        // Startup breadcrumb: confirms in the container logs WHICH endpoint this service targets
        // (e.g. http://dynamodb-local:8000, standalone from LocalStack's SQS endpoint — see
        // AwsProperties#dynamoDbEndpointUrl). Credentials are never logged.
        log.info("Built the DynamoDB client, credentials are never logged | endpoint={} region={} "
                        + "localStackOverride={}",
                aws.dynamoDbEndpointUrl(), aws.region(), localStack.getIfAvailable() != null);
        return builder.build();
    }

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

    /**
     * The audit trail's object store (step 43).
     *
     * <p><b>{@code forcePathStyle}.</b> The SDK defaults to virtual-hosted addressing —
     * {@code http://<bucket>.<host>/<key>} — which needs a wildcard DNS entry per bucket that no local
     * emulator has. Path style puts the bucket in the path instead ({@code http://<host>/<bucket>/<key>}),
     * which is what LocalStack serves. AWS has deprecated path style for new buckets, so this is one of
     * the few places where the local shape genuinely differs from production — and since step 45 the flag
     * <b>does</b> come off there, because it is applied inside the {@code local}-profile branch together
     * with the endpoint override it belongs to (ADR-0013).
     */
    @Bean
    S3Client s3Client(AwsProperties aws, ObjectProvider<LocalStackAwsOverride> localStack) {
        var builder = S3Client.builder().region(Region.of(aws.region()));
        // The local profile — and nothing else — points the client at the emulator (ADR-0013). Absent
        // it, no endpoint and no credentials are passed, so the SDK resolves the ambient role.
        // forcePathStyle rides in the same branch on purpose: it exists for the emulator, a real
        // deployment wants neither, and keeping the two local-only facts together is what stops one of
        // them from being left switched on in production.
        localStack.ifAvailable(override -> override.applyTo(builder).forcePathStyle(true));
        log.info("Built the S3 client for the audit trail, credentials are never logged | endpoint={} "
                        + "region={} localStackOverride={}",
                aws.endpointUrl(), aws.region(), localStack.getIfAvailable() != null);
        return builder.build();
    }

    /**
     * The shared dedup gate (step 29). It takes the platform {@link Clock} rather than reading the
     * system clock itself, so the TTL a test writes is the TTL a test can assert on.
     */
    @Bean
    ProcessedEventStore processedEventStore(DynamoDbClient dynamo, Clock clock) {
        return new ProcessedEventStore(dynamo, clock);
    }
}
