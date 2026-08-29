package com.platinumcoin.pix.common.testsupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.containers.localstack.LocalStackContainer.Service;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Reusable base for LocalStack-backed integration tests ({@code *IT}). Any module can extend this
 * class in its own {@code *IT} to get a disposable DynamoDB emulator that is <b>seeded by the very
 * same init scripts the compose stack runs</b> ({@code infra/localstack/init/*.sh}). Testing against
 * the real init scripts is the whole point: the schema under test is the schema we ship — no
 * hand-maintained test fixture that can silently drift from production (step 08).
 *
 * <p><b>Compose vs. Testcontainers.</b> The compose stack is the manual/E2E playground; these ITs
 * are hermetic and must pass with the compose stack DOWN. The container here is entirely managed by
 * Testcontainers.
 *
 * <p><b>Fast by design — singleton container.</b> The container is a {@code static} field started
 * once per module JVM and never explicitly stopped; every {@code *IT} extending this base reuses it,
 * and Testcontainers' Ryuk sidecar reaps it when the JVM exits. This is the documented
 * "singleton container" pattern — it avoids paying the LocalStack boot + init cost per test class.
 *
 * <p><b>Wiring for Spring services.</b> {@link #awsProperties(DynamicPropertyRegistry)} publishes the
 * container's endpoint/region/credentials under {@code aws.*} keys (relaxed-binding twins of the
 * {@code AWS_ENDPOINT_URL} / {@code AWS_REGION} env vars in {@code docs/local-dev.md}), so a service's
 * {@code @SpringBootTest} IT points its AWS SDK at the container with zero extra code. A non-Spring IT
 * (like the smoke test here) can instead read the endpoint straight off {@link #localstack()}.
 *
 * <p><b>{@code @ActiveProfiles("local")} — and why it is here rather than in each IT</b> (step 45,
 * ADR-0013). Since the credential sweep, an AWS client is pointed at an emulator by <i>one</i> thing:
 * the {@code local} profile, which supplies the endpoint override and the placeholder credentials.
 * Every {@code @SpringBootTest} extending this base talks to a LocalStack container, so every one of
 * them is by definition a local run — a service context started without the profile would correctly
 * fail to find credentials rather than silently reach the emulator. Declaring it once here means an IT
 * cannot forget it, and it is the same profile {@code infra/docker-compose.yml} sets. The annotation is
 * {@code @Inherited}, so subclasses need nothing; Spring-free ITs that build their own client off
 * {@link #localstack()} simply ignore it.
 */
@ActiveProfiles("local")
public abstract class LocalStackTestBase {

    /**
     * Pin the same image tag the compose stack uses ({@code infra/docker-compose.yml}) so tests and
     * the manual playground never diverge on LocalStack behaviour.
     */
    private static final DockerImageName LOCALSTACK_IMAGE = DockerImageName.parse("localstack/localstack:3");

    protected static final LocalStackContainer LOCALSTACK;

    static {
        LocalStackContainer container = new LocalStackContainer(LOCALSTACK_IMAGE)
                // Mirrors SERVICES in compose, widened per sprint as each flow lands: DynamoDB
                // (Sprint 2) + SNS/SQS (Sprint 6, step 26) + S3 (Sprint 10, step 42 — the audit
                // buckets; Sprint 14, step 53 — the export bucket). LocalStack ENFORCES this list — an unlisted service answers 501 — so an
                // init script for a service missing here would abort under `set -e` and hang every
                // IT on the readiness wait below.
                .withServices(Service.DYNAMODB, Service.SNS, Service.SQS, Service.S3)
                // Ready only once the *last* init script has finished. LocalStack opens port 4566
                // before ready.d runs, so waiting on the port alone would race the init; we wait on
                // the last script's final log line instead, guaranteeing tables, seed AND messaging
                // resources are present. This pattern must track whichever script sorts last in
                // ready.d — today 10-statement-exports.sh (step 53; it moved the marker off
                // 09-audit.sh, which had moved it off 08-messaging-notify.sh); appending a script
                // after it moves the marker again. Forgetting costs two minutes and a startup
                // timeout that says nothing about why, in EVERY module.
                .waitingFor(Wait.forLogMessage(".*\\[init\\] statement export ready.*", 1)
                        .withStartupTimeout(Duration.ofMinutes(2)));

        // Mount each real init script into ready.d with the executable bit — LocalStack runs any
        // executable *.sh there in lexical order, exactly as the compose bind-mount does.
        for (Path script : initScripts()) {
            container.withCopyFileToContainer(
                    MountableFile.forHostPath(script, 0_755),
                    "/etc/localstack/init/ready.d/" + script.getFileName());
        }

        LOCALSTACK = container;
        LOCALSTACK.start();
    }

    /**
     * Point a service's AWS SDK at the disposable container. Registered on the Spring
     * {@code Environment} of any extending {@code @SpringBootTest}; unused by non-Spring ITs.
     *
     * <p><b>Background jobs are off in integration tests</b> ({@code pix.schedulers.enabled=false},
     * step 29). Every scheduled component in the platform is conditional on that flag, because a
     * background poller wrecks determinism here in a way no per-test assertion can fix: Spring
     * <i>caches</i> contexts across test classes, so a publisher started by one IT keeps ticking
     * against the shared tables while a later, unrelated IT asserts on them (payment-service's
     * {@code OutboxWriteIT} asserts an outbox event is still unpublished — a live 1s publisher would
     * drain it mid-assertion). An IT that tests a job invokes its tick explicitly instead, which is
     * both deterministic and faster than waiting on a schedule.
     */
    @DynamicPropertySource
    static void awsProperties(DynamicPropertyRegistry registry) {
        registry.add("aws.endpoint-url", () -> LOCALSTACK.getEndpoint().toString());
        registry.add("aws.region", LOCALSTACK::getRegion);
        registry.add("aws.access-key-id", LOCALSTACK::getAccessKey);
        registry.add("aws.secret-access-key", LOCALSTACK::getSecretKey);
        registry.add("pix.schedulers.enabled", () -> "false");
    }

    /** The shared container, for ITs that build their own AWS clients (no Spring context). */
    protected static LocalStackContainer localstack() {
        return LOCALSTACK;
    }

    /**
     * Locate {@code infra/localstack/init} by walking up from the module's working directory to the
     * repo root — robust whether Maven runs the module standalone or from the reactor.
     */
    private static List<Path> initScripts() {
        Path dir = Path.of(System.getProperty("user.dir"));
        while (dir != null) {
            Path candidate = dir.resolve("infra/localstack/init");
            if (Files.isDirectory(candidate)) {
                try (Stream<Path> files = Files.list(candidate)) {
                    return files.filter(p -> p.getFileName().toString().endsWith(".sh"))
                            .sorted()
                            .toList();
                } catch (IOException e) {
                    throw new UncheckedIOException("Failed to list init scripts in " + candidate, e);
                }
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "Could not locate infra/localstack/init walking up from " + System.getProperty("user.dir"));
    }
}
