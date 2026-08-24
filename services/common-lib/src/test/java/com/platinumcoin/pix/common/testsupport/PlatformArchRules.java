package com.platinumcoin.pix.common.testsupport;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The two ArchUnit rules that are the <b>same statement in every service</b>, so they are written once
 * and checked seven times — unlike the ADR-0010/ADR-0011 rules, which each service states in its own
 * {@code *ArchitectureTest} because the packages they name are that service's own.
 *
 * <p>Added by the step-45 hardening gate for the two sweeps whose whole value is that <i>no</i> service
 * is an exception: the API-versioning review (task 3) and the AWS-credential posture (task 5, ADR-0013).
 * A convention that holds in six services and not the seventh is not a convention; making both of them
 * a build failure is the only version worth having.
 */
public final class PlatformArchRules {

    /**
     * Where a controller is allowed to be mounted, and why each prefix is on the list.
     *
     * <ul>
     *   <li>{@code /v1} — the public API. URI versioning (ARCHITECTURE §7.8): changes within a version
     *       are additive-only, and a breaking change gets a {@code /v2} served side by side, because
     *       mobile clients lag and cannot be migrated on our schedule.</li>
     *   <li>{@code /internal} — the internal port (step 68, ADR-0017). Deliberately <b>not</b> versioned:
     *       its only callers are other services in this repository, deployed together, so the
     *       compatibility problem URI versioning solves does not exist there. Versioning it would be
     *       ceremony that implies a stability promise nobody is asking for.</li>
     *   <li>{@code /actuator} — Spring Boot's, not ours to version.</li>
     * </ul>
     *
     * <p><b>What this rule actually protects.</b> The failure it prevents is mundane and permanent: one
     * controller added at {@code /payments} instead of {@code /v1/payments}, shipped, and then
     * un-shippable — because the fix is a breaking change for whoever already integrated. Versioning is
     * cheap on the day the route is written and expensive on every day after it.
     *
     * <p>{@code mock-bacen-spi} is not covered, and could not be: it plays an <i>external</i> system
     * ({@code /spi/**}, {@code /admin/config}, {@code /simulate}) whose URLs are BACEN's shape, not
     * ours. It ships no {@code *ArchitectureTest} at all (CLAUDE.md's stub scope note).
     */
    private static final List<String> ALLOWED_PREFIXES = List.of("/v1", "/internal", "/actuator");

    private PlatformArchRules() {
    }

    /** Task 3: no public route escapes {@code /v1}. */
    public static ArchRule everyControllerIsMountedUnderAVersionedOrInternalPrefix() {
        return classes()
                .that().areAnnotatedWith(RestController.class)
                .should(beMountedUnderAnAllowedPrefix())
                .as("every controller is mounted under /v1 (public, versioned), /internal (step 68) "
                        + "or /actuator — ARCHITECTURE §7.8");
    }

    /**
     * Task 5 / ADR-0013: <b>no service carries a static AWS credential</b>.
     *
     * <p>The rule is stated as an absolute — nothing in a service package may touch
     * {@code software.amazon.awssdk.auth.credentials..} at all — and it can be, because the one
     * legitimate use lives in common-lib's {@code LocalStackAwsOverride}, behind the {@code local}
     * profile. So a service that needs to name a credentials provider is a service doing something
     * ADR-0013 forbids, and there is no exemption to carve out.
     *
     * <p>This is the regression guard the per-service {@code AwsCredentialPostureTest} cannot be: that
     * test proves today's configuration is right, this one prevents tomorrow's new client from quietly
     * reintroducing the shape the sweep removed.
     */
    public static ArchRule noServiceCarriesAStaticAwsCredential() {
        return noClasses()
                .should().dependOnClassesThat().resideInAPackage("software.amazon.awssdk.auth.credentials..")
                .as("AWS credentials are the SDK's ambient chain; only common-lib's LocalStackAwsOverride, "
                        + "under the local profile, may override that (ADR-0013)");
    }

    private static ArchCondition<JavaClass> beMountedUnderAnAllowedPrefix() {
        return new ArchCondition<>("be mounted under " + String.join(", ", ALLOWED_PREFIXES)) {
            @Override
            public void check(JavaClass controller, ConditionEvents events) {
                Set<String> paths = mountedPaths(controller);
                if (paths.isEmpty()) {
                    // A controller with no path anywhere would be mounted at "/", which is neither
                    // versioned nor intentional — report it rather than pass it by default.
                    events.add(SimpleConditionEvent.violated(controller,
                            controller.getName() + " declares no request path at all"));
                    return;
                }
                for (String path : paths) {
                    boolean allowed = ALLOWED_PREFIXES.stream().anyMatch(
                            prefix -> path.equals(prefix) || path.startsWith(prefix + "/"));
                    events.add(new SimpleConditionEvent(controller, allowed,
                            controller.getName() + " is mounted at " + path));
                }
            }
        };
    }

    /**
     * The paths a controller is mounted at: its class-level {@code @RequestMapping} when it has one,
     * and otherwise the paths on its handler methods — a controller that puts the whole path on each
     * method is unusual here but perfectly legal, and skipping it would leave the one shape that can
     * escape the rule unchecked.
     *
     * <p>Both {@code value()} and {@code path()} are read: Spring resolves those aliases with its own
     * annotation machinery, and plain reflection — which is what ArchUnit hands us — sees only the
     * attribute that was actually written.
     */
    private static Set<String> mountedPaths(JavaClass controller) {
        Set<String> paths = new LinkedHashSet<>();
        controller.tryGetAnnotationOfType(RequestMapping.class).ifPresent(mapping -> {
            paths.addAll(List.of(mapping.value()));
            paths.addAll(List.of(mapping.path()));
        });
        if (!paths.isEmpty()) {
            return paths;
        }
        for (JavaMethod method : controller.getMethods()) {
            method.tryGetAnnotationOfType(GetMapping.class)
                    .ifPresent(m -> paths.addAll(union(m.value(), m.path())));
            method.tryGetAnnotationOfType(PostMapping.class)
                    .ifPresent(m -> paths.addAll(union(m.value(), m.path())));
            method.tryGetAnnotationOfType(PutMapping.class)
                    .ifPresent(m -> paths.addAll(union(m.value(), m.path())));
            method.tryGetAnnotationOfType(PatchMapping.class)
                    .ifPresent(m -> paths.addAll(union(m.value(), m.path())));
            method.tryGetAnnotationOfType(DeleteMapping.class)
                    .ifPresent(m -> paths.addAll(union(m.value(), m.path())));
            method.tryGetAnnotationOfType(RequestMapping.class)
                    .ifPresent(m -> paths.addAll(union(m.value(), m.path())));
        }
        return paths;
    }

    private static List<String> union(String[] value, String[] path) {
        return Stream.concat(Stream.of(value), Stream.of(path)).distinct().toList();
    }
}
