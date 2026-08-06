package com.platinumcoin.pix.payment;

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
 * ADR-0010 + ADR-0011 enforcement for payment-service, present from day one per the new-service
 * checklist (CLAUDE.md). Copy of {@code LedgerArchitectureTest}: same two rules, same reasons.
 */
class PaymentArchitectureTest {

    private static final JavaClasses PAYMENT_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.platinumcoin.pix.payment");

    /**
     * ADR-0010 rule 1 — {@code domain/} is plain Java. Fails the build if a domain type
     * ({@code Transaction}, {@code Money}, a use case) ever imports a framework, AWS SDK or
     * JWT-library package, which is what keeps DynamoDB specifics inside {@code infra/}.
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

        rule.check(PAYMENT_CLASSES);
    }

    /**
     * ADR-0011 rule 6 — a controller may not reach an outbound port. Every port is an
     * <b>interface</b> in {@code domain/} ({@code TransactionRepository}) and every use case is a
     * <b>class</b>, so forbidding {@code api/ → interface in domain/} makes "the controller writes the
     * transaction directly" impossible to merge rather than merely discouraged.
     */
    @Test
    void apiDoesNotReachOutboundPorts() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..api..")
                .should().dependOnClassesThat(and(INTERFACES, resideInAPackage("..domain..")))
                .as("api/ must call use cases, never an outbound port (ADR-0011)");

        rule.check(PAYMENT_CLASSES);
    }
}
