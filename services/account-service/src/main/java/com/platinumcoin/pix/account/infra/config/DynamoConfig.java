package com.platinumcoin.pix.account.infra.config;

import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * Composition root for the AWS SDK: builds the single {@link DynamoDbClient} the repository uses,
 * pointed at LocalStack via {@link AwsProperties}. Kept in {@code infra/} so no AWS type ever leaks
 * toward the domain (ADR-0010). The client is thread-safe and long-lived — one bean per service.
 */
@Configuration
@EnableConfigurationProperties(AwsProperties.class)
public class DynamoConfig {

    private static final Logger log = LoggerFactory.getLogger(DynamoConfig.class);

    @Bean
    DynamoDbClient dynamoDbClient(AwsProperties aws) {
        // Startup breadcrumb: confirms in the container logs WHICH endpoint this service targets
        // (e.g. http://localstack:4566). Credentials are never logged.
        log.info("Built the DynamoDB client, credentials are never logged | endpoint={} region={}",
                aws.endpointUrl(), aws.region());
        return DynamoDbClient.builder()
                .endpointOverride(URI.create(aws.endpointUrl()))
                .region(Region.of(aws.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(aws.accessKeyId(), aws.secretAccessKey())))
                .build();
    }
}
