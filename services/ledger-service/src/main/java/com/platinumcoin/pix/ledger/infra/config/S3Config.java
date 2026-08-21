package com.platinumcoin.pix.ledger.infra.config;

import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * The object-storage client, added with the statement cold archive (step 43): ledger-service is the only
 * service allowed to read {@code pix_ledger}, so it is the service that archives it (ADR-0006 — services
 * do not share tables, and archiving needs no atomicity that would justify an exception).
 *
 * <p><b>{@code forcePathStyle}.</b> The SDK's default virtual-hosted addressing
 * ({@code http://<bucket>.<host>}) needs a DNS entry per bucket that no local emulator has; path style
 * puts the bucket in the path instead, which is what LocalStack serves. One of the few genuinely
 * local-only settings in the platform.
 */
@Configuration
public class S3Config {

    private static final Logger log = LoggerFactory.getLogger(S3Config.class);

    @Bean
    S3Client s3Client(AwsProperties aws) {
        log.info("Built the S3 client for the statement cold archive, credentials are never logged | "
                + "endpoint={} region={} forcePathStyle=true", aws.endpointUrl(), aws.region());
        return S3Client.builder()
                .endpointOverride(URI.create(aws.endpointUrl()))
                .region(Region.of(aws.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(aws.accessKeyId(), aws.secretAccessKey())))
                .forcePathStyle(true)
                .build();
    }
}
