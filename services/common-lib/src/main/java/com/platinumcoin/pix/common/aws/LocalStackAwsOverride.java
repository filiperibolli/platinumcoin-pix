package com.platinumcoin.pix.common.aws;

import java.net.URI;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;

/**
 * <b>The one place in production code where a static AWS credential is written down</b>, and it is
 * reachable only under the {@code local} Spring profile (ADR-0013, swept in step 45).
 *
 * <h2>What the shape is saying</h2>
 * Before this class, every AWS client in every service was built with an {@code endpointOverride} and a
 * {@code StaticCredentialsProvider("test", "test")} <i>unconditionally</i>. Locally that is harmless —
 * LocalStack validates no signature and reads the access key only to derive the account id
 * {@code 000000000000}, so it is a signing formality, not authentication. The problem ADR-0013 names is
 * not the fake key: it is that <b>the shape of the code was indistinguishable from the production
 * anti-pattern</b> (a long-lived key/secret pair baked into a service), leaving a reader unable to tell
 * a deliberate local override from a misunderstanding.
 *
 * <p>Now the default build passes <b>neither</b> the endpoint nor the credentials, so the SDK's
 * {@code DefaultCredentialsProvider} chain resolves the ambient role — ECS task role, EKS IRSA/Pod
 * Identity, or EC2 instance profile — all of which hand out temporary STS credentials the SDK rotates
 * on its own. There is no long-lived secret on the production path because there is no code that could
 * supply one.
 *
 * <h2>Why it is a bean rather than an {@code if}</h2>
 * A conditional inside each client builder would still <i>read</i> the credentials on every path, and
 * would have to be repeated (and kept identical) in six configuration classes. As a bean that exists
 * only under {@code local}, the absence is the configuration: a service started without the profile
 * cannot construct one, and its clients take the ambient chain because there is nothing to apply. Each
 * client injects it as an {@code ObjectProvider}, so "no override" is an ordinary, expected state
 * rather than a missing-bean failure.
 *
 * <h2>The consequence to know about</h2>
 * Local runs need the profile active. The compose stack sets it
 * ({@code SPRING_PROFILES_ACTIVE:-local}) and {@code LocalStackTestBase} activates it for every
 * integration test — but a service started by hand without it will fail to find credentials rather than
 * silently talk to LocalStack, which ADR-0013 chose deliberately: a loud failure beats a service that
 * looks configured for production and is not. If you set {@code SPRING_PROFILES_ACTIVE} yourself,
 * include {@code local} (e.g. {@code json-logs,local}) — see docs/local-dev.md §3.
 *
 * <h2>What this class does not do</h2>
 * It grants nothing. Authorization is the per-service least-privilege policy in {@code infra/iam/},
 * which LocalStack accepts and does <b>not</b> enforce ({@code ENFORCE_IAM} is off by default and gated
 * as a paid feature), so no local test can prove a denial. Those policies are reviewed as documents;
 * this class only decides who we say we are.
 *
 * @param endpointUrl         LocalStack's endpoint for SNS/SQS/S3
 * @param dynamoDbEndpointUrl the standalone {@code amazon/dynamodb-local} endpoint, separate since
 *                            {@code docs/load/BOTTLENECK.md}; falls back to {@code endpointUrl}
 * @param accessKeyId         the emulator's placeholder key — a signing formality, never a secret
 * @param secretAccessKey     ditto
 */
public record LocalStackAwsOverride(
        String endpointUrl,
        String dynamoDbEndpointUrl,
        String accessKeyId,
        String secretAccessKey) {

    /**
     * Point a client builder at LocalStack with the placeholder credentials.
     *
     * <p>{@code AwsClientBuilder} extends {@code SdkClientBuilder}, so one bound covers both the
     * credential and the endpoint call and every SDK client builder satisfies it — DynamoDB, SNS, SQS
     * and S3 alike, with no per-service overload.
     */
    public <B extends AwsClientBuilder<B, ?>> B applyTo(B builder, String endpoint) {
        return builder
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKeyId, secretAccessKey)));
    }

    /** {@link #applyTo(AwsClientBuilder, String)} against the SNS/SQS/S3 endpoint. */
    public <B extends AwsClientBuilder<B, ?>> B applyTo(B builder) {
        return applyTo(builder, endpointUrl);
    }

    /** {@link #applyTo(AwsClientBuilder, String)} against the standalone DynamoDB endpoint. */
    public <B extends AwsClientBuilder<B, ?>> B applyToDynamoDb(B builder) {
        return applyTo(builder, dynamoDbEndpointUrl == null ? endpointUrl : dynamoDbEndpointUrl);
    }

    /**
     * The same override, exposed as its two parts, for a builder that {@link #applyTo} cannot type.
     *
     * <h2>Why this exists (step 53)</h2>
     * {@code S3Presigner.Builder} is an {@code SdkPresigner} builder, not an {@code AwsClientBuilder},
     * so it does not satisfy {@link #applyTo}'s bound — and the first attempt at that presigner built
     * its own {@code StaticCredentialsProvider} inside payment-service, which is precisely the shape
     * ADR-0013 removed. The ArchUnit rule
     * ({@code PlatformArchRules.noServiceCarriesAStaticAwsCredential}) caught it, and the honest fix is
     * this pair of accessors rather than an exception to the rule: the credential is still constructed
     * <b>only here</b>, in the one class that exists only under the {@code local} profile, and a caller
     * receives it already built.
     *
     * <p>The alternative — an S3-typed overload — would make common-lib depend on the S3 SDK for the
     * sake of one service's presigner. These two accessors depend on nothing new.
     */
    public AwsCredentialsProvider credentialsProvider() {
        return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(accessKeyId, secretAccessKey));
    }

    /** The SNS/SQS/S3 endpoint as a {@link URI}, for the same callers as {@link #credentialsProvider()}. */
    public URI endpointUri() {
        return URI.create(endpointUrl);
    }
}
