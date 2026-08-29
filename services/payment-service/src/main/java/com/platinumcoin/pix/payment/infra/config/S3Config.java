package com.platinumcoin.pix.payment.infra.config;

import com.platinumcoin.pix.common.aws.LocalStackAwsOverride;
import com.platinumcoin.pix.common.metrics.AwsSdkDependencyMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * payment-service's object-storage clients, added with the cold statement export (step 53). Two
 * buckets, two directions: it <b>reads</b> {@code pix-statement-archive} (ledger-service's artifact,
 * step 43) and <b>writes</b> {@code pix-statement-exports} (its own).
 *
 * <h2>Why a presigner is a second client</h2>
 * {@link S3Presigner} does not talk to S3 at all — it signs a URL locally with the same credentials and
 * region, and hands it to a customer's browser to use directly. That is the whole point: the artifact
 * bytes never pass through this service, so a two-year export is a redirect rather than a response
 * streamed through a JVM that has a request thread and a heap to lose. It is a separate builder because
 * the SDK models it as one, and it takes the same local-profile override for the same reason — a signed
 * URL must name the host the browser can actually reach.
 *
 * <p><b>{@code forcePathStyle}.</b> Virtual-hosted addressing ({@code http://<bucket>.<host>}) needs a
 * DNS entry per bucket that no local emulator has; path style puts the bucket in the path, which is what
 * LocalStack serves. Local-only, so it rides in the same {@code local}-profile branch as the endpoint
 * override rather than being left switched on in production (ADR-0013).
 *
 * <p><b>Neither client carries a credential or an endpoint of its own</b> (ADR-0013): the SDK's
 * {@code DefaultCredentialsProvider} chain resolves the ambient role, and only
 * {@link LocalStackAwsConfig} — a {@code @Profile("local")} bean — redirects them at the emulator.
 */
@Configuration
public class S3Config {

    private static final Logger log = LoggerFactory.getLogger(S3Config.class);

    @Bean
    S3Client s3Client(AwsProperties aws, AwsSdkDependencyMetrics dependencyMetrics,
            ObjectProvider<LocalStackAwsOverride> localStack) {
        var builder = S3Client.builder()
                .region(Region.of(aws.region()))
                // Every call this client makes is timed into pix.dependency.seconds (step 72).
                .overrideConfiguration(override -> override.addMetricPublisher(dependencyMetrics));
        localStack.ifAvailable(override -> override.applyTo(builder).forcePathStyle(true));
        log.info("Built the S3 client for the statement archive and the export artifacts, credentials "
                        + "are never logged | endpoint={} region={} localStackOverride={}",
                aws.endpointUrl(), aws.region(), localStack.getIfAvailable() != null);
        return builder.build();
    }

    /**
     * The URL signer. It performs no network call, so it needs no metric publisher and cannot fail at
     * startup — but it does need the same endpoint override, or every link it mints would point at the
     * real AWS host and 404 for a customer running the sandbox.
     *
     * <p><b>Why the override is applied field by field</b> rather than through
     * {@link LocalStackAwsOverride#applyTo}: {@code S3Presigner.Builder} is an {@code SdkPresigner}
     * builder, not an {@code AwsClientBuilder}, so it does not satisfy that method's bound. The first
     * draft of this bean built its own {@code StaticCredentialsProvider} here — and the ArchUnit rule
     * of ADR-0013 ({@code noStaticAwsCredentialLivesInThisService}) failed the build for it, correctly:
     * "only common-lib's local-profile override may construct a credential" is the whole point, and a
     * presigner is not an exception to it. So the credential is <b>received</b> already built from that
     * one class ({@link LocalStackAwsOverride#credentialsProvider()}), and this service still contains
     * no code that could supply one.
     */
    @Bean
    S3Presigner s3Presigner(AwsProperties aws, ObjectProvider<LocalStackAwsOverride> localStack) {
        var builder = S3Presigner.builder().region(Region.of(aws.region()));
        localStack.ifAvailable(override -> builder
                .endpointOverride(override.endpointUri())
                .credentialsProvider(override.credentialsProvider())
                // Path style for the same reason the client uses it: a signed virtual-hosted URL would
                // name a per-bucket DNS host the browser cannot resolve against the emulator.
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build()));
        log.info("Built the S3 presigner for export download links, credentials are never logged | "
                        + "endpoint={} region={} localStackOverride={}",
                aws.endpointUrl(), aws.region(), localStack.getIfAvailable() != null);
        return builder.build();
    }
}
