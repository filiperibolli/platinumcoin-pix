package com.platinumcoin.pix.account;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * ADR-0010 enforcement for account-service: {@code domain/} is plain Java and must not reach outward.
 * account-service is the first service with an AWS SDK dependency, so this rule earns its keep — it
 * fails the build if {@code Account} or {@code AccountRepository} ever imports an
 * {@code software.amazon.awssdk..} (or web/servlet/JWT) type, keeping the DynamoDB adapter isolated
 * in {@code infra/}.
 */
class AccountArchitectureTest {

    private static final JavaClasses ACCOUNT_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.platinumcoin.pix.account");

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
}
