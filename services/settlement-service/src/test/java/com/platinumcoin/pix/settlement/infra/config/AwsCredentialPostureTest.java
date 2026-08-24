package com.platinumcoin.pix.settlement.infra.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.platinumcoin.pix.common.aws.LocalStackAwsOverride;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * <b>The credential posture of settlement-service, asserted rather than promised</b> (ADR-0013, step 45).
 *
 * <p>ADR-0013's decision is a <i>negative</i> one — "no long-lived credential exists on the production
 * code path" — and a negative is exactly what a happy-path test never checks. Every integration test in
 * this module runs with the {@code local} profile on and would stay green if the override leaked back
 * into the default build; the emulator would simply be reached twice as hard. So the property under test
 * here is the <b>absence</b>: without the profile, there is no {@link LocalStackAwsOverride} bean, so
 * nothing can hand a client an endpoint or a static key, and the SDK's
 * {@code DefaultCredentialsProvider} chain resolves the ambient role (ECS task role / EKS IRSA / EC2
 * instance profile).
 *
 * <p>A unit test on the configuration class, not a {@code @SpringBootTest}: booting the whole service
 * without the profile would fail on the AWS calls it makes at startup, which proves the credentials are
 * missing only by way of a crash. {@link ApplicationContextRunner} asks the narrower, exact question.
 *
 * <p>What it deliberately does <b>not</b> claim: nothing here says the ambient role is granted the right
 * permissions. That is {@code infra/iam/settlement-service-policy.json}, which LocalStack accepts and does not
 * enforce — reviewed as a document, unprovable locally, and honest about it.
 */
class AwsCredentialPostureTest {

    /** The credentials are supplied as properties, exactly as {@code application.yml} does. */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(LocalStackAwsConfig.class)
            .withBean(AwsProperties.class, () -> new AwsProperties(
                    "http://localhost:4566", "http://localhost:8000", "us-east-1", "test", "test"));

    @Test
    void theDefaultBuildHasNoStaticCredentialAndNoEndpointOverride() {
        runner.run(context -> assertThat(context)
                .as("without the local profile nothing can override the SDK's credential chain")
                .doesNotHaveBean(LocalStackAwsOverride.class));
    }

    @Test
    void theLocalProfileIsTheOnlyThingThatPointsAClientAtTheEmulator() {
        runner.withPropertyValues("spring.profiles.active=local").run(context -> {
            assertThat(context).hasSingleBean(LocalStackAwsOverride.class);
            assertThat(context.getBean(LocalStackAwsOverride.class).endpointUrl())
                    .isEqualTo("http://localhost:4566");
            assertThat(context.getBean(LocalStackAwsOverride.class).dynamoDbEndpointUrl())
                    .as("DynamoDB is a standalone container, not LocalStack (docs/load/BOTTLENECK.md)")
                    .isEqualTo("http://localhost:8000");
        });
    }
}
