package com.platinumcoin.pix.auth;

import com.platinumcoin.pix.common.testsupport.PlatformArchRules;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.base.DescribedPredicate.and;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.INTERFACES;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * ADR-0010 + ADR-0011 enforcement for auth-service: the dependency rule ({@code api → domain},
 * {@code infra → domain}) and the use-case boundary cannot silently rot, because both fail the build.
 */
class AuthArchitectureTest {

    private static final JavaClasses AUTH_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.platinumcoin.pix.auth");

    /** ADR-0010 rule 1 — no web/AWS/servlet/JWT-library import may reach {@code domain/}. */
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
                .as("domain/ must not depend on framework, infra or JWT-library packages");

        rule.check(AUTH_CLASSES);
    }

    /**
     * ADR-0011 rule 6 — {@code api/} calls use cases, never an outbound port. auth-service's ports
     * ({@code UserRepository}, {@code PasswordVerifier}, {@code TokenIssuer}) are interfaces in
     * {@code domain/}; {@code LoginUseCase} is a class. So a controller that tried to verify a
     * password or mint a token itself would fail the build.
     */
    @Test
    void apiDoesNotReachOutboundPorts() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..api..")
                .should().dependOnClassesThat(and(INTERFACES, resideInAPackage("..domain..")))
                .as("api/ must call use cases, never an outbound port (ADR-0011)");

        rule.check(AUTH_CLASSES);
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
        PlatformArchRules.everyControllerIsMountedUnderAVersionedOrInternalPrefix().check(AUTH_CLASSES);
    }

    /**
     * <b>ADR-0013 — no static AWS credential outside common-lib's {@code local}-profile override</b>
     * (step 45). The regression guard the per-service {@code AwsCredentialPostureTest} cannot be: that
     * test proves today's wiring is right, this one stops tomorrow's new client from quietly
     * reintroducing the shape the sweep removed.
     */
    @Test
    void noStaticAwsCredentialLivesInThisService() {
        PlatformArchRules.noServiceCarriesAStaticAwsCredential().check(AUTH_CLASSES);
    }
}
