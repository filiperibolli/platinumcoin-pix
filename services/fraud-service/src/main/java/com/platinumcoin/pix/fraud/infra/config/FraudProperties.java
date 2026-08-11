package com.platinumcoin.pix.fraud.infra.config;

import com.platinumcoin.pix.fraud.domain.model.FraudRules;
import java.time.Duration;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Binds {@code fraud.rules.*} from {@code application.yml} — the Spring-aware edge that keeps {@code
 * @ConfigurationProperties} (and therefore Spring) out of the domain. It carries two things the domain
 * splits apart: the pure scoring knobs, handed to the use case as a plain {@link FraudRules} via {@link
 * #toRules()}, and the two Redis <b>window durations</b>, which are a storage concern of {@code
 * RedisFraudSignalStore} alone (the use case never sees them).
 *
 * <p>Constructor-bound record with {@link DefaultValue} on every field, so the service still boots with
 * a sane, documented rule set even if a key is missing; {@code application.yml} carries the real values.
 * Amounts are integer cents.
 */
@ConfigurationProperties(prefix = "fraud.rules")
public record FraudProperties(
        @DefaultValue("500000") long highAmountCents,
        @DefaultValue("5") int velocityCountThreshold,
        @DefaultValue("2000000") long velocityAmountThresholdCents,
        @DefaultValue("60s") Duration countWindow,
        @DefaultValue("1h") Duration amountWindow,
        @DefaultValue("0") int oddHoursStartHour,
        @DefaultValue("5") int oddHoursEndHour,
        @DefaultValue("America/Sao_Paulo") String zone,
        @DefaultValue("70") int highAmountWeight,
        @DefaultValue("40") int velocityCountWeight,
        @DefaultValue("40") int velocityAmountWeight,
        @DefaultValue("15") int newPayeeWeight,
        @DefaultValue("10") int oddHoursWeight,
        @DefaultValue("40") int reviewBand,
        @DefaultValue("70") int denyBand) {

    /** The scoring knobs the framework-free use case consumes (no window durations — a storage concern). */
    public FraudRules toRules() {
        return new FraudRules(
                highAmountCents,
                velocityCountThreshold,
                velocityAmountThresholdCents,
                oddHoursStartHour,
                oddHoursEndHour,
                ZoneId.of(zone),
                highAmountWeight,
                velocityCountWeight,
                velocityAmountWeight,
                newPayeeWeight,
                oddHoursWeight,
                reviewBand,
                denyBand);
    }
}
