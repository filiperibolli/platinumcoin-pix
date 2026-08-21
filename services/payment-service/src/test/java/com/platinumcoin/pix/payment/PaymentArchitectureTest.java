package com.platinumcoin.pix.payment;

import com.platinumcoin.pix.payment.domain.port.BalanceCache;
import com.platinumcoin.pix.payment.domain.usecase.GetBalanceUseCase;
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

    /**
     * <b>ADR-0008's correctness rule, enforced by the build</b> (step 40). The balance cache serves
     * display reads and nothing else: a value from Redis may be up to one TTL old, so the moment any
     * money-moving code path could read it, "the cache cannot cause an overdraft" stops being a
     * property of the design and becomes a promise someone has to keep.
     *
     * <p>The rule is stated as "in {@code domain/}, only {@link GetBalanceUseCase} may depend on
     * {@link BalanceCache}" — which is precisely what makes it impossible for {@code SendPixUseCase} to
     * grow a "check the balance first" shortcut. (It would be a read-then-check race even with a
     * perfectly fresh cache; the guard belongs inside the ledger's conditional write, Domain Safety
     * Rule #3.) The port itself is excluded because a type trivially references itself.
     */
    @Test
    void onlyTheBalanceReadDependsOnTheBalanceCache() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..domain..")
                .and().doNotHaveFullyQualifiedName(GetBalanceUseCase.class.getName())
                .and().doNotHaveFullyQualifiedName(BalanceCache.class.getName())
                .should().dependOnClassesThat().haveFullyQualifiedName(BalanceCache.class.getName())
                .as("only GetBalanceUseCase may read the balance cache — no money decision may (ADR-0008)");

        rule.check(PAYMENT_CLASSES);
    }
}
