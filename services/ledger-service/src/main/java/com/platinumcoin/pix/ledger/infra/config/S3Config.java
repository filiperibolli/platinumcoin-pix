package com.platinumcoin.pix.ledger.infra.config;

import com.platinumcoin.pix.common.aws.LocalStackAwsOverride;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
 * local-only settings in the platform — so since step 45 it is applied under the {@code local} profile
 * together with the endpoint override, rather than left switched on in production (ADR-0013).
 */
@Configuration
public class S3Config {

    private static final Logger log = LoggerFactory.getLogger(S3Config.class);

    @Bean
    S3Client s3Client(AwsProperties aws, ObjectProvider<LocalStackAwsOverride> localStack) {
        var builder = S3Client.builder().region(Region.of(aws.region()));
        // The local profile — and nothing else — points the client at the emulator (ADR-0013). Absent
        // it, no endpoint and no credentials are passed, so the SDK resolves the ambient role.
        // forcePathStyle rides in the same branch on purpose: it exists for the emulator, a real
        // deployment wants neither, and keeping the two local-only facts together is what stops one of
        // them from being left switched on in production.
        localStack.ifAvailable(override -> override.applyTo(builder).forcePathStyle(true));
        log.info("Built the S3 client for the statement cold archive, credentials are never logged | "
                        + "endpoint={} region={} localStackOverride={}",
                aws.endpointUrl(), aws.region(), localStack.getIfAvailable() != null);
        return builder.build();
    }
}
