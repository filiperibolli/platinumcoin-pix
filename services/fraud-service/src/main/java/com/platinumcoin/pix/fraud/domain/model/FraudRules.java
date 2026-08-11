package com.platinumcoin.pix.fraud.domain.model;

import java.time.ZoneId;

/**
 * The tuning knobs of the rule engine, as a plain-Java value object so {@link
 * com.platinumcoin.pix.fraud.domain.usecase.ScoreFraudUseCase} stays framework-free and unit-testable
 * (a test builds one with fixed values; the running service builds one from {@code fraud.rules.*} via
 * {@code FraudProperties#toRules()} — the {@code @ConfigurationProperties} adapter lives in {@code
 * infra/}, keeping Spring out of {@code domain/}).
 *
 * <p>The <b>thresholds</b> decide which reasons fire; the <b>weights</b> turn fired reasons into a
 * cumulative score; the two <b>bands</b> turn the score into a {@link Decision} ({@code score >=
 * denyBand} ⇒ DENY, else {@code score >= reviewBand} ⇒ REVIEW, else APPROVE). Amounts are integer
 * cents ({@code long}) end to end — never a floating value.
 *
 * <p>Note what is <i>absent</i>: the Redis window durations (60s / 3600s). Those are a storage concern
 * of the counter adapter, not a scoring rule — the use case only ever compares the returned totals to
 * these thresholds, so the window lengths live in {@code FraudProperties} alone.
 *
 * @param highAmountCents             single-transfer value above which {@link FraudReason#HIGH_AMOUNT} fires
 * @param velocityCountThreshold      transfers in the short window at/above which {@link FraudReason#VELOCITY_COUNT} fires
 * @param velocityAmountThresholdCents money in the long window above which {@link FraudReason#VELOCITY_AMOUNT} fires
 * @param oddHoursStartHour           inclusive start hour of the overnight window (local {@code zone})
 * @param oddHoursEndHour             exclusive end hour of the overnight window (local {@code zone})
 * @param zone                        the timezone the transfer time is read in (Pix is domestic: São Paulo)
 * @param highAmountWeight            score contribution of {@link FraudReason#HIGH_AMOUNT}
 * @param velocityCountWeight         score contribution of {@link FraudReason#VELOCITY_COUNT}
 * @param velocityAmountWeight        score contribution of {@link FraudReason#VELOCITY_AMOUNT}
 * @param newPayeeWeight              score contribution of {@link FraudReason#NEW_PAYEE}
 * @param oddHoursWeight              score contribution of {@link FraudReason#ODD_HOURS}
 * @param reviewBand                  score at/above which the decision is at least REVIEW
 * @param denyBand                    score at/above which the decision is DENY
 */
public record FraudRules(
        long highAmountCents,
        int velocityCountThreshold,
        long velocityAmountThresholdCents,
        int oddHoursStartHour,
        int oddHoursEndHour,
        ZoneId zone,
        int highAmountWeight,
        int velocityCountWeight,
        int velocityAmountWeight,
        int newPayeeWeight,
        int oddHoursWeight,
        int reviewBand,
        int denyBand) {

    /** True when {@code hour} falls in the configured overnight window {@code [start, end)}. */
    public boolean isOddHour(int hour) {
        return hour >= oddHoursStartHour && hour < oddHoursEndHour;
    }
}
