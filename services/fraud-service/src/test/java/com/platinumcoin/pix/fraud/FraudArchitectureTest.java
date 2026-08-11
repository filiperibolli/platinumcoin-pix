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
 * ADR-0010 + ADR-0011 enforcement for fraud-service. Both rules were present from the skeleton (step 23)
 * before any {@code domain/}/{@code api/} class existed; step 24 added the Redis-backed scoring use case
 * and its controller, so the {@code ..domain..}/{@code ..api..} matches are now non-empty and the two
 * {@code allowEmptyShould(true)} skeleton crutches have been dropped — the rules now guard real classes.
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
                .as("domain/ must not depend on framework, AWS SDK or JWT-library packages");

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
                .as("api/ must call use cases, never an outbound port (ADR-0011)");

        rule.check(FRAUD_CLASSES);
    }
}
