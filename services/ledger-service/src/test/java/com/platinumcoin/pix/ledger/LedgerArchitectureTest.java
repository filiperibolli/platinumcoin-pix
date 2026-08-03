package com.platinumcoin.pix.ledger;

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
 * ADR-0010 + ADR-0011 enforcement for ledger-service, present from day one per the new-service
 * checklist (CLAUDE.md). Copy of {@code AccountArchitectureTest}: same two rules, same reasons.
 *
 * <p>These matter more here than anywhere else in the platform. The ledger is the one service whose
 * invariants are non-negotiable, and both rules exist to keep those invariants in a place where they
 * can be unit-tested without AWS and cannot be bypassed: the money rules live in {@code domain/},
 * and the only way to reach the table is through a port that {@code api/} cannot even see.
 */
class LedgerArchitectureTest {

    private static final JavaClasses LEDGER_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.platinumcoin.pix.ledger");

    /**
     * ADR-0010 rule 1 — {@code domain/} is plain Java. Fails the build if {@code Balance},
     * {@code LedgerEntry} or a use case ever imports a {@code software.amazon.awssdk..} type, which
     * is what keeps the DynamoDB specifics (ConsistentRead here; {@code TransactWriteItems} in
     * step 14) inside {@code infra/}.
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

        rule.check(LEDGER_CLASSES);
    }

    /**
     * ADR-0011 rule 6 — a controller may not reach an outbound port. Every port is an
     * <b>interface</b> in {@code domain/} ({@code LedgerRepository}) and every use case is a
     * <b>class</b>, so forbidding {@code api/ → interface in domain/} makes "the controller reads the
     * ledger directly" impossible to merge rather than merely discouraged.
     */
    @Test
    void apiDoesNotReachOutboundPorts() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..api..")
                .should().dependOnClassesThat(and(INTERFACES, resideInAPackage("..domain..")))
                .as("api/ must call use cases, never an outbound port (ADR-0011)");

        rule.check(LEDGER_CLASSES);
    }
}
