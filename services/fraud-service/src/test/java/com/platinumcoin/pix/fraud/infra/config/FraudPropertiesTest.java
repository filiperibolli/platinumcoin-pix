package com.platinumcoin.pix.fraud.infra.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.platinumcoin.pix.fraud.domain.model.FraudRules;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Proves that {@link FraudProperties}'s {@code @DefaultValue} fallbacks — what the service binds to
 * when {@code fraud.rules.*} is entirely absent from configuration — produce byte-for-byte the same
 * {@link FraudRules} that {@code ScoreFraudUseCaseTest} hand-builds and that
 * {@code src/main/resources/application.yml}'s base document currently spells out explicitly. This is
 * the guardrail for A4 (docs/load/RESULTS.md): the {@code loadtest} profile only ever narrows what it
 * touches, and this test is what makes "the defaults are unchanged" a build failure instead of a claim.
 */
class FraudPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertyPlaceholderAutoConfiguration.class, EnabledConfig.class);

    @EnableConfigurationProperties(FraudProperties.class)
    static class EnabledConfig {
    }

    @Test
    void defaultValuesBindWithNoConfigurationAtAll() {
        contextRunner.run(context -> {
            FraudProperties properties = context.getBean(FraudProperties.class);

            assertThat(properties.highAmountCents()).isEqualTo(500_000L);
            assertThat(properties.velocityCountThreshold()).isEqualTo(5);
            assertThat(properties.velocityAmountThresholdCents()).isEqualTo(2_000_000L);
            assertThat(properties.oddHoursStartHour()).isZero();
            assertThat(properties.oddHoursEndHour()).isEqualTo(5);
            assertThat(properties.zone()).isEqualTo("America/Sao_Paulo");
            assertThat(properties.highAmountWeight()).isEqualTo(70);
            assertThat(properties.velocityCountWeight()).isEqualTo(40);
            assertThat(properties.velocityAmountWeight()).isEqualTo(40);
            assertThat(properties.newPayeeWeight()).isEqualTo(15);
            assertThat(properties.oddHoursWeight()).isEqualTo(10);
            assertThat(properties.reviewBand()).isEqualTo(40);
            assertThat(properties.denyBand()).isEqualTo(70);
        });
    }

    @Test
    void defaultRulesMatchWhatScoreFraudUseCaseTestHandBuilds() {
        contextRunner.run(context -> {
            FraudRules rules = context.getBean(FraudProperties.class).toRules();

            FraudRules expected = new FraudRules(
                    500_000L,
                    5,
                    2_000_000L,
                    0, 5,
                    ZoneId.of("America/Sao_Paulo"),
                    70, 40, 40, 15, 10,
                    40, 70);

            assertThat(rules).isEqualTo(expected);
        });
    }

    /**
     * Loads the REAL {@code src/main/resources/application.yml} from the classpath (not a hand-built
     * context) with no profile active, and checks it binds to exactly the same values as the
     * {@code @DefaultValue} fallbacks above — i.e. the base document and the framework defaults have
     * not drifted apart.
     */
    @Test
    void baseApplicationYamlMatchesTheDefaultValueFallbacks() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(EnabledConfig.class)
                .web(WebApplicationType.NONE)
                .run()) {
            FraudProperties properties = context.getBean(FraudProperties.class);

            assertThat(properties.toRules()).isEqualTo(new FraudRules(
                    500_000L, 5, 2_000_000L, 0, 5, ZoneId.of("America/Sao_Paulo"),
                    70, 40, 40, 15, 10, 40, 70));
        }
    }

    /**
     * Activates the {@code loadtest} profile against the REAL {@code application.yml} and asserts it
     * narrows exactly what docs/load/RESULTS.md documents: the two velocity thresholds move, and
     * every other threshold, weight and band — HIGH_AMOUNT, NEW_PAYEE, ODD_HOURS, both bands — stays
     * byte-for-byte at its default. A future edit that widens the profile's blast radius fails this
     * test instead of silently shipping.
     */
    @Test
    void loadtestProfileRaisesOnlyTheVelocityThresholds() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(EnabledConfig.class)
                .web(WebApplicationType.NONE)
                .profiles("loadtest")
                .run()) {
            FraudProperties properties = context.getBean(FraudProperties.class);

            assertThat(properties.velocityCountThreshold()).isEqualTo(1_000_000);
            assertThat(properties.velocityAmountThresholdCents()).isEqualTo(100_000_000_000L);

            // Untouched: HIGH_AMOUNT, NEW_PAYEE, ODD_HOURS, both weights not covered above, and both bands.
            assertThat(properties.highAmountCents()).isEqualTo(500_000L);
            assertThat(properties.oddHoursStartHour()).isZero();
            assertThat(properties.oddHoursEndHour()).isEqualTo(5);
            assertThat(properties.zone()).isEqualTo("America/Sao_Paulo");
            assertThat(properties.highAmountWeight()).isEqualTo(70);
            assertThat(properties.velocityCountWeight()).isEqualTo(40);
            assertThat(properties.velocityAmountWeight()).isEqualTo(40);
            assertThat(properties.newPayeeWeight()).isEqualTo(15);
            assertThat(properties.oddHoursWeight()).isEqualTo(10);
            assertThat(properties.reviewBand()).isEqualTo(40);
            assertThat(properties.denyBand()).isEqualTo(70);
        }
    }
}
