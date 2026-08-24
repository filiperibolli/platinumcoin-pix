package com.platinumcoin.pix.notification;

import static com.tngtech.archunit.base.DescribedPredicate.and;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.INTERFACES;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.platinumcoin.pix.common.testsupport.PlatformArchRules;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * ADR-0010 + ADR-0011 enforcement for notification-service, both rules present from the service's first
 * step. Copies {@code AccountArchitectureTest}.
 *
 * <p>They earn their keep unusually fast here, because this is the service whose whole reason to exist
 * is a <b>framework type</b>: {@code SseEmitter} is Spring MVC's async-response abstraction, and the
 * natural way to write a push service is to let it spread — a registry of emitters in the domain, a use
 * case that calls {@code emitter.send()}, and suddenly the routing rule can only be tested with a
 * servlet container running. The generic {@code SubscriberRegistry<S>} exists precisely so that does not
 * happen, and rule 1 below is what proves it did not.
 */
class NotificationArchitectureTest {

    private static final JavaClasses NOTIFICATION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.platinumcoin.pix.notification");

    /**
     * ADR-0010 rule 1 — {@code domain/} is plain Java: no Spring (so no {@code SseEmitter} and no
     * {@code @Scheduled}), no AWS SDK (no SQS type in a use case), no servlet API, no Jackson binding
     * (the queue payload reaches the domain as a plain {@code Map}).
     */
    @Test
    void domainDependsOnNothingOutward() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..",
                        "jakarta.servlet..",
                        "software.amazon.awssdk..",
                        "io.jsonwebtoken..",
                        "com.fasterxml.jackson..")
                .as("domain/ must not depend on framework, AWS SDK or JWT-library packages");

        rule.check(NOTIFICATION_CLASSES);
    }

    /**
     * ADR-0011 rule 6 — an inbound adapter may not reach an outbound port. All three inbound adapters
     * here are covered by it: the controller must not touch the emitter registry, the queue consumer
     * must not dedupe by itself, and the heartbeat job must not sweep the channel directly. Each has to
     * go through its use case, which is where the policy is and where a plain-Java test can pin it.
     */
    @Test
    void apiDoesNotReachOutboundPorts() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..api..")
                .should().dependOnClassesThat(and(INTERFACES, resideInAPackage("..domain..")))
                .as("api/ must call use cases, never an outbound port (ADR-0011)");

        rule.check(NOTIFICATION_CLASSES);
    }

    /**
     * <b>ARCHITECTURE §7.8 — no public route escapes {@code /v1}</b> (step 45). Shared with every other
     * service ({@link PlatformArchRules}) because it is the same sentence everywhere; a versioning
     * convention that holds in six services and not the seventh is not a convention.
     *
     * <p>The failure it prevents is mundane and permanent: one controller mounted at {@code /payments}
     * instead of {@code /v1/payments}, shipped, and then un-shippable — because the fix is a breaking
     * change for whoever already integrated. Versioning costs nothing on the day the route is written.
     */
    @Test
    void everyPublicRouteIsVersioned() {
        PlatformArchRules.everyControllerIsMountedUnderAVersionedOrInternalPrefix().check(NOTIFICATION_CLASSES);
    }

    /**
     * <b>ADR-0013 — no static AWS credential outside common-lib's {@code local}-profile override</b>
     * (step 45). The regression guard the per-service {@code AwsCredentialPostureTest} cannot be: that
     * test proves today's wiring is right, this one stops tomorrow's new client from quietly
     * reintroducing the shape the sweep removed.
     */
    @Test
    void noStaticAwsCredentialLivesInThisService() {
        PlatformArchRules.noServiceCarriesAStaticAwsCredential().check(NOTIFICATION_CLASSES);
    }
}
