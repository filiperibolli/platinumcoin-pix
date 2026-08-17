package com.platinumcoin.pix.settlement.infra.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AWS SDK connection config, bound from {@code aws.*} — the relaxed-binding twins of the
 * {@code AWS_ENDPOINT_URL} / {@code AWS_REGION} / {@code AWS_ACCESS_KEY_ID} / {@code AWS_SECRET_ACCESS_KEY}
 * env vars the compose stack sets (docs/local-dev.md §3), and the very same keys
 * {@code LocalStackTestBase} publishes for ITs — so an {@code @SpringBootTest} points the SDK at the
 * disposable container with zero extra wiring.
 *
 * <p>LocalStack ignores the credentials but the SDK refuses to build without them, hence the
 * {@code test}/{@code test} placeholders. ADR-0013 schedules the sweep to the default
 * credentials-provider chain for all services at once in step 45; until then every new client copies
 * this shape, because two competing shapes mid-migration are worse than one uniform shape awaiting a
 * single reviewable change.
 *
 * <p>{@code dynamoDbEndpointUrl} exists because the compose stack's DynamoDB client points at a
 * standalone {@code amazon/dynamodb-local} container instead of LocalStack (docs/load/BOTTLENECK.md):
 * LocalStack proxies every DynamoDB call through an internal Python HTTP client with a fixed,
 * non-configurable connection-pool cap, which became the load-test throughput ceiling. SQS still goes
 * through LocalStack (see {@code AwsClientsConfig#sqsClient}, which keeps using {@code endpointUrl}).
 * Defaults to {@code endpointUrl} ({@code ${DYNAMODB_ENDPOINT_URL:${aws.endpoint-url}}} in
 * application.yml) so ITs (which only override {@code aws.endpoint-url}) are unaffected.
 */
@ConfigurationProperties(prefix = "aws")
public record AwsProperties(
        String endpointUrl,
        String dynamoDbEndpointUrl,
        String region,
        String accessKeyId,
        String secretAccessKey) {
}
