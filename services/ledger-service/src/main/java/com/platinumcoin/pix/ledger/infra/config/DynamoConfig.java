package com.platinumcoin.pix.ledger.infra.config;

import com.platinumcoin.pix.common.aws.LocalStackAwsOverride;
import com.platinumcoin.pix.common.metrics.AwsSdkDependencyMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * Composition root for the AWS SDK: builds the single {@link DynamoDbClient} the ledger adapter uses.
 * Kept in {@code infra/} so no AWS type ever leaks toward the domain (ADR-0010). The client is
 * thread-safe and long-lived — one bean per service.
 *
 * <p><b>It carries no credential and no endpoint of its own</b> (ADR-0013, step 45): the SDK's
 * {@code DefaultCredentialsProvider} chain resolves the ambient role, and only
 * {@link LocalStackAwsConfig} — a {@code @Profile("local")} bean — redirects it at the emulator. The
 * region stays here because a region is configuration, not a credential.
 */
@Configuration
@EnableConfigurationProperties(AwsProperties.class)
public class DynamoConfig {

    private static final Logger log = LoggerFactory.getLogger(DynamoConfig.class);

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
        // (e.g. http://dynamodb-local:8000, standalone from LocalStack's SNS/SQS endpoint — see
        // AwsProperties#dynamoDbEndpointUrl). Credentials are never logged.
        log.info("Built the DynamoDB client, credentials are never logged | endpoint={} region={} "
                        + "localStackOverride={}",
                aws.dynamoDbEndpointUrl(), aws.region(), localStack.getIfAvailable() != null);
        return builder.build();
    }
}
