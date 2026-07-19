package com.platinumcoin.pix.common;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Not a behavior test — a wiring probe. Proves the Java 21 toolchain compiles,
 * surefire discovers *Test, and the Spring Boot / JUnit BOM resolves end to end.
 */
class PlaceholderTest {

    @Test
    void moduleIsWiredForTests() {
        assertTrue(Placeholder.ready());
    }
}
