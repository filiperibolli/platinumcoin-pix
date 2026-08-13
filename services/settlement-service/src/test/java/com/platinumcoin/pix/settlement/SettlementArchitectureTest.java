package com.platinumcoin.pix.settlement;

import static com.tngtech.archunit.base.DescribedPredicate.and;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.INTERFACES;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * ADR-0010 + ADR-0011 enforcement for settlement-service, present from day one per the new-service
 * checklist (CLAUDE.md). Copy of {@code PaymentArchitectureTest}: same two rules, same reasons.
 */
class SettlementArchitectureTest {

    private static final JavaClasses SETTLEMENT_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.platinumcoin.pix.settlement");

    /**
     * ADR-0010 rule 1 — {@code domain/} is plain Java. It matters more here than anywhere: the settlement
     * decision is the one place where a wrong reaction to an SQS or DynamoDB error sends money twice, and
     * keeping the AWS SDK out of {@code domain/} is what makes that decision testable without either.
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

        rule.check(SETTLEMENT_CLASSES);
    }

    /**
     * ADR-0011 rule 6 — an inbound adapter may not reach an outbound port. The queue consumer is an
     * inbound adapter exactly like a controller, so this rule is what stops it from "just" calling the
     * transaction store or the SPI client directly and quietly growing a second, untested settlement
     * path. Every port is an <b>interface</b> in {@code domain/}, every use case is a <b>class</b>, so
     * the rule is exact and needs no naming convention.
     */
    @Test
    void apiDoesNotReachOutboundPorts() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..api..")
                .should().dependOnClassesThat(and(INTERFACES, resideInAPackage("..domain..")))
                .as("api/ must call use cases, never an outbound port (ADR-0011)");

        rule.check(SETTLEMENT_CLASSES);
    }
}
