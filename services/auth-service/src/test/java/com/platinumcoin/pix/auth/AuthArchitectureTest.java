package com.platinumcoin.pix.auth;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * ADR-0010 enforcement for auth-service: {@code domain/} is plain Java and must not reach outward.
 * The build fails if a domain type imports a web/AWS/servlet/persistence/JWT-library package, so
 * the dependency rule ({@code api → domain}, {@code infra → domain}) cannot silently rot.
 */
class AuthArchitectureTest {

    private static final JavaClasses AUTH_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.platinumcoin.pix.auth");

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
}
