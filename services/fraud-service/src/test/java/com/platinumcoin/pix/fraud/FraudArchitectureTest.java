package com.platinumcoin.pix.fraud;

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
 * ADR-0010 + ADR-0011 enforcement for fraud-service, present from the skeleton (step 23) — both rules
 * exist before the first {@code domain/}/{@code api/} class lands (step 24), so the very first violation
 * fails the build instead of a reviewer's memory. On the endpoint-less skeleton the rules hold vacuously;
 * they earn their keep the moment step 24 adds the Redis-backed scoring use case and its controller.
 *
 * <p><b>{@code allowEmptyShould(true)} is deliberate and temporary.</b> ArchUnit fails a rule that
 * matches <i>zero</i> classes by default (a guard against typo'd package names). The skeleton genuinely
 * has no {@code ..domain..}/{@code ..api..} class yet, so the flag lets the rules pass vacuously now;
 * step 24 adds those layers and the match becomes non-empty, at which point the flag is a no-op and may
 * be dropped.
 */
class FraudArchitectureTest {

    private static final JavaClasses FRAUD_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.platinumcoin.pix.fraud");

    /**
     * ADR-0010 rule 1 — {@code domain/} is plain Java. fraud-service's outward dependency is the Redis
     * client (Lettuce via Spring Data Redis): this rule fails the build if a domain type ever imports a
     * {@code org.springframework..} (or web/servlet/JWT/Jackson) type, keeping the Redis adapter isolated
     * in {@code infra/}.
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
                .as("domain/ must not depend on framework, AWS SDK or JWT-library packages")
                .allowEmptyShould(true); // step-23 skeleton has no domain/ yet; step 24 adds it.

        rule.check(FRAUD_CLASSES);
    }

    /**
     * ADR-0011 rule 6 — a controller may not reach an outbound port. Every port is an <b>interface</b> in
     * {@code domain/} and every use case is a <b>class</b>, so forbidding {@code api/ → interface in
     * domain/} lets controllers keep calling use cases while making a direct Redis-port call impossible
     * to merge.
     */
    @Test
    void apiDoesNotReachOutboundPorts() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..api..")
                .should().dependOnClassesThat(and(INTERFACES, resideInAPackage("..domain..")))
                .as("api/ must call use cases, never an outbound port (ADR-0011)")
                .allowEmptyShould(true); // step-23 skeleton has no api/ yet; step 24 adds it.

        rule.check(FRAUD_CLASSES);
    }
}
