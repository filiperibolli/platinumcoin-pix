package com.platinumcoin.pix.payment.infra.config;

import com.platinumcoin.pix.common.aws.LocalStackAwsOverride;
import com.platinumcoin.pix.common.metrics.AwsSdkDependencyMetrics;
import com.platinumcoin.pix.common.tracing.TracePropagation;
import com.platinumcoin.pix.payment.domain.port.EventPublisher;
import com.platinumcoin.pix.payment.infra.client.SnsEventPublisher;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;

/**
 * The SNS half of the AWS composition root (step 29): one {@link SnsClient} and the
 * {@link EventPublisher} adapter bound to the {@code pix-events} topic.
 *
 * <p><b>The topic ARN is configuration, not a lookup.</b> The service is handed the ARN it must publish
 * to ({@code pix.events.topic-arn}, overridable with {@code SNS_TOPIC_ARN}) rather than discovering it
 * by name at startup: a deployed service is granted {@code sns:Publish} on exactly one ARN (ADR-0013)
 * and has no business listing or creating topics, and a boot-time AWS call would make startup depend on
 * the emulator being not just up but initialized. The default is the ARN LocalStack mints for the topic
 * {@code 06-messaging-core.sh} creates.
 *
 * <p><b>The client carries no credential and no endpoint of its own</b> (ADR-0013, swept in step 45):
 * the SDK's {@code DefaultCredentialsProvider} chain resolves the ambient role, and only
 * {@link LocalStackAwsConfig} — a {@code @Profile("local")} bean — redirects it at the emulator. The
 * ARN above is the authorization boundary's other half: a deployed payment-service is granted
 * {@code sns:Publish} on exactly that topic and <b>no</b> SQS permission at all
 * ({@code infra/iam/payment-service-policy.json}).
 */
@Configuration
@EnableConfigurationProperties(AwsProperties.class)
public class SnsConfig {

    private static final Logger log = LoggerFactory.getLogger(SnsConfig.class);

    @Bean
    SnsClient snsClient(AwsProperties aws, AwsSdkDependencyMetrics dependencyMetrics,
            ObjectProvider<LocalStackAwsOverride> localStack) {
        var builder = SnsClient.builder()
                .region(Region.of(aws.region()))
                // Every call this client makes is timed into pix.dependency.seconds (step 72). Attached
                // per client because the SDK offers no global hook — an explicit line beats a publisher
                // that silently measures nothing.
                .overrideConfiguration(override -> override.addMetricPublisher(dependencyMetrics));
        // The local profile — and nothing else — points the client at the emulator (ADR-0013). Absent
        // it, no endpoint and no credentials are passed, so the SDK resolves the ambient role.
        localStack.ifAvailable(override -> override.applyTo(builder));
        log.info("Built the SNS client, credentials are never logged | endpoint={} region={} "
                        + "localStackOverride={}",
                aws.endpointUrl(), aws.region(), localStack.getIfAvailable() != null);
        return builder.build();
    }

    /**
     * {@code ObjectProvider} for the tracing collaborators, not a plain parameter: they exist only when
     * Boot's tracing auto-configuration is active, and the publisher must remain constructible without
     * them. Publishing an event is on the money path; observability never gets to be a startup dependency
     * of it (ADR-0021).
     */
    @Bean
    EventPublisher eventPublisher(
            SnsClient sns,
            @Value("${pix.events.topic-arn}") String topicArn,
            ObjectProvider<Tracer> tracer,
            ObjectProvider<TracePropagation> tracing) {
        TracePropagation propagation = tracing.getIfAvailable();
        log.info("Wired the outbox publisher to its SNS topic | topicArn={} tracingEnabled={}",
                topicArn, propagation != null);
        return new SnsEventPublisher(sns, topicArn, tracer.getIfAvailable(), propagation);
    }
}
