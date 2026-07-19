package com.platinumcoin.pix.auth;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The context loads. This is the walking-skeleton guardrail: from now on, any change
 * that breaks Spring wiring (a bad bean, a mis-imported common-lib auto-configuration,
 * a broken application.yml) fails the build here — forever after. Because auth-service
 * pulls the common-lib JSON-logging include and its @ConditionalOnClass web
 * auto-configuration, a green context also proves common-lib ↔ service integration,
 * not merely that Spring boots.
 *
 * <p>Named *IT so it runs under failsafe on `mvn verify` (Testcontainers-style
 * boundary), consistent with the repo's unit(*Test)/integration(*IT) split.
 */
@SpringBootTest
class ApplicationContextIT {

    @Test
    void contextLoads() {
        // Intentionally empty: success is the context starting without throwing.
    }
}
