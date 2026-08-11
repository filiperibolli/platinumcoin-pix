package com.platinumcoin.pix.common.testsupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

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
 */
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
                // (Sprint 2) + SNS/SQS (Sprint 6, step 26). LocalStack ENFORCES this list — an
                // unlisted service answers 501 — so an init script for a service missing here would
                // abort under `set -e` and hang every IT on the readiness wait below.
                .withServices(Service.DYNAMODB, Service.SNS, Service.SQS)
                // Ready only once the *last* init script has finished. LocalStack opens port 4566
                // before ready.d runs, so waiting on the port alone would race the init; we wait on
                // the last script's final log line instead, guaranteeing tables, seed AND messaging
                // resources are present. This pattern must track whichever script sorts last in
                // ready.d — today 06-messaging-core.sh (step 26); appending a script after it moves
                // the marker.
                .waitingFor(Wait.forLogMessage(".*\\[init\\] messaging ready.*", 1)
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
     */
    @DynamicPropertySource
    static void awsProperties(DynamicPropertyRegistry registry) {
        registry.add("aws.endpoint-url", () -> LOCALSTACK.getEndpoint().toString());
        registry.add("aws.region", LOCALSTACK::getRegion);
        registry.add("aws.access-key-id", LOCALSTACK::getAccessKey);
        registry.add("aws.secret-access-key", LOCALSTACK::getSecretKey);
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
