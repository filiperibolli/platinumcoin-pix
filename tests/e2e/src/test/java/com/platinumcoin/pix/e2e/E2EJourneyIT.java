package com.platinumcoin.pix.e2e;

import com.platinumcoin.pix.common.testsupport.MoneyConservation;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * <b>Step 46 — the whole platform, in one run.</b> Eight processes, a real queue, a real emulated rail,
 * a real cache and a real DynamoDB: login → register key → balance → internal Pix (with the retry that
 * must not double it) → external Pix → settlement → the payer's push → an inbound Pix → the payee's push
 * → statement → two failure drills → conservation of money across every account.
 *
 * <h2>Why this class drives {@code scripts/e2e-journey.sh} instead of restating its assertions in Java</h2>
 * The journey has one definition, and it is the script. Re-expressing forty cross-service assertions in
 * a second language would produce exactly the failure mode this repo treats as a defect everywhere else
 * — twin artifacts that drift (the Postman collection and the API explorer are held to the same rule in
 * CLAUDE.md, and {@link MoneyConservation}'s own javadoc explains why three modules must not develop
 * three ideas of conservation). What this class adds is what a shell script cannot: a place for
 * {@code mvn} and CI to hang the journey, and a <b>second, independent measurement of KR1.1</b> — Σ
 * balances read through the AWS SDK, in a different process and a different client stack from the CLI
 * the script uses, wrapped around the entire run. If the two ever disagreed, one of them is reading the
 * ledger wrong, and that is worth knowing.
 *
 * <h2>Why it is not in the default reactor</h2>
 * Every other {@code *IT} here is hermetic and passes with the compose stack DOWN (docs/local-dev.md
 * §6). This one is the deliberate exception — see the {@code e2e} profile's comment in the parent POM.
 * Run it with the stack up:
 *
 * <pre>{@code
 * docker compose -f infra/docker-compose.yml up -d --build
 * mvn -Pe2e -pl tests/e2e -am verify
 * }</pre>
 *
 * <h2>Why a missing stack FAILS rather than skips</h2>
 * A skipped end-to-end test is indistinguishable from a passing one in a build log, and this project's
 * workflow forbids marking a step done with skipped tests. So an unreachable stack is an explicit
 * failure carrying the command that fixes it — never an {@code assumeTrue} that turns a broken
 * environment into a green build.
 */
class E2EJourneyIT {

    /** The repo root, found by walking up from this module rather than assuming a working directory. */
    private static final Path REPO_ROOT = Path.of(System.getProperty("user.dir")).toAbsolutePath()
            .resolve("../..").normalize();

    private static final String PAYMENT_HEALTH = "http://localhost:8084/actuator/health/readiness";
    private static final String DDB_ENDPOINT =
            System.getenv().getOrDefault("DYNAMODB_ENDPOINT_URL", "http://localhost:8000");

    /**
     * The drills wait on the platform's shipped timers — the SQS backoff ladder into the DLQ (~135s) and
     * the 5-minute reconciliation SLO — because a drill run against tuned-down thresholds tests the test.
     * The ceiling here is therefore generous on purpose: it exists to stop a hung run, not to bound a
     * healthy one.
     */
    private static final Duration JOURNEY_CEILING = Duration.ofMinutes(15);

    @Test
    @DisplayName("the full journey composes, the failure drills recover, and money is conserved")
    void theWholeJourney() throws Exception {
        requireRunningStack();

        long sigmaBefore = sigmaBalances();

        int exitCode = runJourneyScript();

        long sigmaAfter = sigmaBalances();
        // Read through the SDK, around the whole run, using the platform's one shared definition of
        // conservation. The script asserts this too; that is the point — two independent readings.
        MoneyConservation.assertConserved(
                "the full end-to-end journey, including a dead-lettered settlement that was redriven "
                        + "and a permanently refused payment that was reversed",
                sigmaBefore, sigmaAfter);

        assertThat(exitCode)
                .as("scripts/e2e-journey.sh exit code — 0 means every cross-service assertion held; "
                        + "the failing ones are named in the script output above")
                .isZero();
    }

    /**
     * Runs the journey and streams its output into this test's output as it happens. Streaming rather
     * than capturing matters: the drills spend minutes waiting on real timers, and a test that prints
     * nothing for four minutes is indistinguishable from a hung one.
     */
    private int runJourneyScript() throws IOException, InterruptedException {
        Path script = REPO_ROOT.resolve("scripts/e2e-journey.sh");
        assertThat(Files.isExecutable(script))
                .as("scripts/e2e-journey.sh must exist and be executable at %s", script)
                .isTrue();

        Process process = new ProcessBuilder("bash", script.toString())
                .directory(REPO_ROOT.toFile())
                .redirectErrorStream(true)
                .start();

        try (BufferedReader out = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = out.readLine()) != null) {
                System.out.println("[e2e-journey] " + line);
            }
        }

        if (!process.waitFor(JOURNEY_CEILING.toSeconds(), TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("the journey did not finish within " + JOURNEY_CEILING.toMinutes() + " minutes — "
                    + "something is hung, not merely slow");
        }
        return process.exitValue();
    }

    /**
     * Σ balanceCents over every account partition, read from the book of record.
     *
     * <p>Deliberately not read from {@code GET /v1/accounts/me/balance}: that endpoint is per-account,
     * is only readable with a customer token (so it cannot see {@code SPI_CLEARING}, {@code SPI_SETTLED}
     * or the {@code SEED} counterpart at all), and is served from the Redis cache (ADR-0008). Asserting
     * conservation through a cache would let a stale read hide a lost cent — which is the exact class of
     * bug this assertion exists to catch.
     */
    private long sigmaBalances() {
        try (DynamoDbClient dynamo = DynamoDbClient.builder()
                .endpointOverride(URI.create(DDB_ENDPOINT))
                .region(Region.US_EAST_1)
                // The placeholder pair the emulator needs to derive an account id — never
                // authentication (ADR-0013). Static, so the SDK never falls back to an ambient role and
                // reaches somebody's real AWS account from a test.
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test", "test")))
                .build()) {

            long sigma = 0L;
            Map<String, AttributeValue> lastKey = null;
            do {
                ScanRequest.Builder request = ScanRequest.builder()
                        .tableName("pix_ledger")
                        // One mutable BALANCE item per account partition; the ENTRY items are history
                        // and summing them would be a different (and much larger) question.
                        .filterExpression("sk = :b")
                        .expressionAttributeValues(Map.of(":b", AttributeValue.fromS("BALANCE")))
                        .projectionExpression("pk, balanceCents");
                if (lastKey != null && !lastKey.isEmpty()) {
                    request.exclusiveStartKey(lastKey);
                }
                var response = dynamo.scan(request.build());
                for (Map<String, AttributeValue> item : response.items()) {
                    // Integer cents end to end — parsed as a long, never a double (Domain safety rule #6).
                    sigma += Long.parseLong(item.get("balanceCents").n());
                }
                lastKey = response.lastEvaluatedKey();
            } while (lastKey != null && !lastKey.isEmpty());
            return sigma;
        }
    }

    /** A stack that is not up is an environment failure with a fix attached, not a platform verdict. */
    private void requireRunningStack() {
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(PAYMENT_HEALTH))
                            .timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode())
                    .as("payment-service readiness at %s", PAYMENT_HEALTH)
                    .isEqualTo(200);
        } catch (Exception e) {
            fail(String.join("\n", List.of(
                    "The compose stack is not up, so this end-to-end test cannot prove anything.",
                    "  mvn clean package -DskipTests",
                    "  docker compose -f infra/docker-compose.yml up -d --build",
                    "then re-run: mvn -Pe2e -pl tests/e2e -am verify",
                    "(cause: " + e + ")")));
        }
    }
}
