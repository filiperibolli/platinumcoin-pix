package com.platinumcoin.pix.account;

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
 * ADR-0010 + ADR-0011 enforcement for account-service. Two rules, each turning a written convention
 * into a build failure rather than a reviewer's memory.
 */
class AccountArchitectureTest {

    private static final JavaClasses ACCOUNT_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.platinumcoin.pix.account");

    /**
     * ADR-0010 rule 1 — {@code domain/} is plain Java. account-service is the first service with an
     * AWS SDK dependency, so this rule earns its keep: it fails the build if {@code Account} or a use
     * case ever imports a {@code software.amazon.awssdk..} (or web/servlet/JWT) type, keeping the
     * DynamoDB adapter isolated in {@code infra/}.
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

        rule.check(ACCOUNT_CLASSES);
    }

    /**
     * ADR-0011 rule 6 — a controller may not reach an outbound port. This is the mechanical form of
     * "no business logic in {@code api/}": every port is an <b>interface</b> in {@code domain/}
     * ({@code AccountRepository}, {@code PixKeyRepository}) and every use case is a <b>class</b>, so
     * forbidding {@code api/ → interface in domain/} lets controllers keep calling use cases and
     * reshaping records while making a direct repository call impossible to merge.
     *
     * <p>Consequence to know before fighting this test: introducing a use case as an interface would
     * trip it. That is intended — a use case has one implementation, and ADR-0010 rule 2 says a
     * single-implementation non-boundary gets no interface.
     */
    @Test
    void apiDoesNotReachOutboundPorts() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..api..")
                .should().dependOnClassesThat(and(INTERFACES, resideInAPackage("..domain..")))
                .as("api/ must call use cases, never an outbound port (ADR-0011)");

        rule.check(ACCOUNT_CLASSES);
    }
}
