package com.platinumcoin.pix.ledger.infra.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AWS SDK connection config, bound from {@code aws.*} — the relaxed-binding twins of the
 * {@code AWS_ENDPOINT_URL} / {@code AWS_REGION} / {@code AWS_ACCESS_KEY_ID} / {@code AWS_SECRET_ACCESS_KEY}
 * env vars the compose stack sets (docs/local-dev.md §3), and the very same keys
 * {@code LocalStackTestBase} publishes for ITs — so an {@code @SpringBootTest} points the SDK at the
 * disposable container with zero extra wiring.
 *
 * <p>LocalStack ignores the credentials but the SDK refuses to build without them, hence the
 * {@code test}/{@code test} placeholders. A deployed service drops the endpoint override and lets the
 * default credential provider chain (IAM role) supply real ones.
 */
@ConfigurationProperties(prefix = "aws")
public record AwsProperties(
        String endpointUrl,
        String region,
        String accessKeyId,
        String secretAccessKey) {
}
