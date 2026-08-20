package com.platinumcoin.pix.notification.infra.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AWS SDK connection config, bound from {@code aws.*} — the relaxed-binding twins of the
 * {@code AWS_ENDPOINT_URL} / {@code AWS_REGION} / {@code AWS_ACCESS_KEY_ID} /
 * {@code AWS_SECRET_ACCESS_KEY} env vars the compose stack sets (docs/local-dev.md §3), and the same
 * keys {@code LocalStackTestBase} publishes for ITs.
 *
 * <p>{@code dynamoDbEndpointUrl} is separate because the compose stack's DynamoDB client points at a
 * standalone {@code amazon/dynamodb-local} container rather than LocalStack (docs/load/BOTTLENECK.md),
 * while SQS still goes through LocalStack. It falls back to {@code endpointUrl}, so ITs — which
 * override only {@code aws.endpoint-url} — are unaffected. Copied verbatim from settlement-service:
 * ADR-0013 sweeps every service to the default credentials-provider chain in one reviewable change at
 * step 45, and two competing shapes mid-migration would be worse than one uniform shape awaiting it.
 */
@ConfigurationProperties(prefix = "aws")
public record AwsProperties(
        String endpointUrl,
        String dynamoDbEndpointUrl,
        String region,
        String accessKeyId,
        String secretAccessKey) {
}
