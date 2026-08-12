package com.platinumcoin.pix.payment.infra.config;

import com.platinumcoin.pix.payment.domain.port.EventPublisher;
import com.platinumcoin.pix.payment.infra.client.SnsEventPublisher;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
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
 * <p>Credentials follow the shape of {@link DynamoConfig} deliberately: ADR-0013 schedules the sweep to
 * the default credentials-provider chain for <b>all</b> services at once in step 45, and two competing
 * shapes mid-migration would be worse than one uniform shape awaiting a single reviewable change.
 */
@Configuration
@EnableConfigurationProperties(AwsProperties.class)
public class SnsConfig {

    private static final Logger log = LoggerFactory.getLogger(SnsConfig.class);

    @Bean
    SnsClient snsClient(AwsProperties aws) {
        log.info("Built the SNS client, credentials are never logged | endpoint={} region={}",
                aws.endpointUrl(), aws.region());
        return SnsClient.builder()
                .endpointOverride(URI.create(aws.endpointUrl()))
                .region(Region.of(aws.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(aws.accessKeyId(), aws.secretAccessKey())))
                .build();
    }

    @Bean
    EventPublisher eventPublisher(SnsClient sns, @Value("${pix.events.topic-arn}") String topicArn) {
        log.info("Wired the outbox publisher to its SNS topic | topicArn={}", topicArn);
        return new SnsEventPublisher(sns, topicArn);
    }
}
