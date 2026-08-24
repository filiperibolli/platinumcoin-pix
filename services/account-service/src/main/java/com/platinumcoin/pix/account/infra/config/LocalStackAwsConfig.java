package com.platinumcoin.pix.account.infra.config;

import com.platinumcoin.pix.common.aws.LocalStackAwsOverride;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * The LocalStack override for account-service's AWS clients — DynamoDB (accounts + pix keys) — active <b>only</b> under the
 * {@code local} profile (ADR-0013, swept in step 45).
 *
 * <p><b>The absence of this bean is the production configuration.</b> Without the profile there is no
 * {@link LocalStackAwsOverride} to apply, so every client builder in this service passes neither an
 * {@code endpointOverride} nor a {@code credentialsProvider} and the SDK's
 * {@code DefaultCredentialsProvider} chain resolves the ambient role (ECS task role / EKS IRSA / EC2
 * instance profile), which hands out temporary STS credentials it rotates on its own. No long-lived
 * key/secret exists on that path, because no code on it can supply one.
 *
 * <p>Deliberately its own file rather than a {@code @Profile} method tucked inside the client
 * configuration: {@code grep -rl LocalStackAwsConfig services/} is then the complete answer to "where
 * does this platform still use static AWS credentials?", and the answer is five files that all say the
 * same thing.
 *
 * <p>A service started without the profile will fail to find credentials rather than silently talk to
 * LocalStack — the loud failure ADR-0013 chose. Compose sets it; {@code LocalStackTestBase} sets it for
 * every integration test; docs/local-dev.md §3 covers running one service by hand.
 */
@Configuration
@Profile("local")
public class LocalStackAwsConfig {

    private static final Logger log = LoggerFactory.getLogger(LocalStackAwsConfig.class);

    @Bean
    LocalStackAwsOverride localStackAwsOverride(AwsProperties aws) {
        log.info("The local profile is active, so AWS clients are pointed at the emulator with "
                        + "placeholder credentials instead of the ambient role (ADR-0013) | endpoint={} "
                        + "dynamoDbEndpoint={} accessKeyId={}",
                aws.endpointUrl(), aws.dynamoDbEndpointUrl(), aws.accessKeyId());
        return new LocalStackAwsOverride(aws.endpointUrl(), aws.dynamoDbEndpointUrl(),
                aws.accessKeyId(), aws.secretAccessKey());
    }
}
