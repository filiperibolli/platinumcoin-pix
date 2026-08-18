package com.platinumcoin.pix.account.infra.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AWS SDK connection config, bound from {@code aws.*}. These are the relaxed-binding twins of the
 * {@code AWS_ENDPOINT_URL} / {@code AWS_REGION} / {@code AWS_ACCESS_KEY_ID} / {@code AWS_SECRET_ACCESS_KEY}
 * env vars used by the compose stack (docs/local-dev.md) — and the very same keys
 * {@code LocalStackTestBase} publishes for ITs, so an {@code @SpringBootTest} points the SDK at the
 * disposable container with zero extra wiring.
 *
 * <p>LocalStack ignores the credentials, but the SDK still refuses to build without them, so we pass
 * the {@code test}/{@code test} placeholders. A deployed service would drop the endpoint override and
 * let the default credential provider chain (IAM role) supply real credentials.
 *
 * <p>{@code dynamoDbEndpointUrl} exists because the compose stack's DynamoDB client points at a
 * standalone {@code amazon/dynamodb-local} container instead of LocalStack (docs/load/BOTTLENECK.md):
 * LocalStack proxies every DynamoDB call through an internal Python HTTP client with a fixed,
 * non-configurable connection-pool cap, which became the load-test throughput ceiling. SNS/SQS still
 * go through LocalStack. Defaults to {@code endpointUrl} ({@code ${DYNAMODB_ENDPOINT_URL:${aws.endpoint-url}}}
 * in application.yml) so ITs (which only override {@code aws.endpoint-url}) are unaffected.
 */
@ConfigurationProperties(prefix = "aws")
public record AwsProperties(
        String endpointUrl,
        String dynamoDbEndpointUrl,
        String region,
        String accessKeyId,
        String secretAccessKey) {
}
