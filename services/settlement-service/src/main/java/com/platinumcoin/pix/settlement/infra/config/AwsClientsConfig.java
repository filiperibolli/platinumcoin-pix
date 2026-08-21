package com.platinumcoin.pix.settlement.infra.config;

import com.platinumcoin.pix.common.event.ProcessedEventStore;
import java.net.URI;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
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
 */
@Configuration
@EnableConfigurationProperties(AwsProperties.class)
public class AwsClientsConfig {

    private static final Logger log = LoggerFactory.getLogger(AwsClientsConfig.class);

    @Bean
    DynamoDbClient dynamoDbClient(AwsProperties aws) {
        // Startup breadcrumb: confirms in the container logs WHICH endpoint this service targets
        // (e.g. http://dynamodb-local:8000, standalone from LocalStack's SQS endpoint — see
        // AwsProperties#dynamoDbEndpointUrl). Credentials are never logged.
        log.info("Built the DynamoDB client, credentials are never logged | endpoint={} region={}",
                aws.dynamoDbEndpointUrl(), aws.region());
        return DynamoDbClient.builder()
                .endpointOverride(URI.create(aws.dynamoDbEndpointUrl()))
                .region(Region.of(aws.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(aws.accessKeyId(), aws.secretAccessKey())))
                .build();
    }

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

    /**
     * The audit trail's object store (step 43).
     *
     * <p><b>{@code forcePathStyle}.</b> The SDK defaults to virtual-hosted addressing —
     * {@code http://<bucket>.<host>/<key>} — which needs a wildcard DNS entry per bucket that no local
     * emulator has. Path style puts the bucket in the path instead ({@code http://<host>/<bucket>/<key>}),
     * which is what LocalStack serves. AWS has deprecated path style for new buckets, so this is one of
     * the few places where the local shape genuinely differs from production and the flag would come off
     * in a real deployment (ADR-0013 territory: the local endpoint override goes with it).
     */
    @Bean
    S3Client s3Client(AwsProperties aws) {
        log.info("Built the S3 client for the audit trail, credentials are never logged | endpoint={} "
                + "region={} forcePathStyle=true", aws.endpointUrl(), aws.region());
        return S3Client.builder()
                .endpointOverride(URI.create(aws.endpointUrl()))
                .region(Region.of(aws.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(aws.accessKeyId(), aws.secretAccessKey())))
                .forcePathStyle(true)
                .build();
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
