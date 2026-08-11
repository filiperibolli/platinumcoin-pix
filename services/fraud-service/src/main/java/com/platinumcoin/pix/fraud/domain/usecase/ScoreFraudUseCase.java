package com.platinumcoin.pix.fraud.domain.usecase;

import com.platinumcoin.pix.fraud.domain.model.Decision;
import com.platinumcoin.pix.fraud.domain.model.FraudReason;
import com.platinumcoin.pix.fraud.domain.model.FraudRules;
import com.platinumcoin.pix.fraud.domain.model.ScoreResult;
import com.platinumcoin.pix.fraud.domain.port.FraudSignalStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The rule-based fraud check that runs <b>inside</b> the send path under a 150ms budget (ARCHITECTURE
 * §6.5, ADR-0005). It reads four cheap, pre-computed features — never a model, never a network hop
 * beyond Redis — combines them into a 0–100 score and maps the score to a {@link Decision} band.
 *
 * <p><b>Order matters and is deliberate (step-24 decision):</b> the three velocity/novelty features are
 * <i>recorded first</i> via the {@link FraudSignalStore}, so the returned totals already include this
 * transfer ("this tx counts itself"). Then the reasons are evaluated against the thresholds in {@link
 * FraudRules}, each firing reason adds its weight, and the capped score picks the band. Everything is a
 * single pass with no branching I/O — the reason the design (not the hardware) is what keeps it fast.
 *
 * <p>The clock is injected (never {@code Instant.now()} in-line, ADR-0011) and is only a <i>fallback</i>:
 * the odd-hours rule prefers the transfer's own timestamp, using the clock when the caller omits it.
 */
public class ScoreFraudUseCase {

    private static final Logger log = LoggerFactory.getLogger(ScoreFraudUseCase.class);

    private static final int MAX_SCORE = 100;

    private final FraudSignalStore signals;
    private final FraudRules rules;
    private final Clock clock;

    public ScoreFraudUseCase(FraudSignalStore signals, FraudRules rules, Clock clock) {
        this.signals = signals;
        this.rules = rules;
        this.clock = clock;
    }

    public ScoreResult execute(ScoreCommand command) {
        log.info("Fraud score requested, evaluating in-path rules | accountId={} pixKey={} amountCents={} timestamp={}",
                command.accountId(), command.pixKey(), command.amountCents(), command.timestamp());

        // Record-then-read: each feature now includes the current transfer (velocity counts itself).
        long recentCount = signals.recordAndCountRecent(command.accountId());
        long recentAmount = signals.recordAndSumRecentAmount(command.accountId(), command.amountCents());
        boolean newPayee = signals.recordPayeeReturningIsNew(command.accountId(), command.pixKey());

        Instant when = command.timestamp() != null ? command.timestamp() : Instant.now(clock);
        int hour = ZonedDateTime.ofInstant(when, rules.zone()).getHour();

        log.debug("Fraud features read from Redis | accountId={} recentCount={} recentAmountCents={} "
                        + "newPayee={} localHour={} zone={}",
                command.accountId(), recentCount, recentAmount, newPayee, hour, rules.zone());

        List<FraudReason> reasons = new ArrayList<>();
        int score = 0;

        if (command.amountCents() > rules.highAmountCents()) {
            reasons.add(FraudReason.HIGH_AMOUNT);
            score += rules.highAmountWeight();
        }
        if (recentCount >= rules.velocityCountThreshold()) {
            reasons.add(FraudReason.VELOCITY_COUNT);
            score += rules.velocityCountWeight();
        }
        if (recentAmount > rules.velocityAmountThresholdCents()) {
            reasons.add(FraudReason.VELOCITY_AMOUNT);
            score += rules.velocityAmountWeight();
        }
        if (newPayee) {
            reasons.add(FraudReason.NEW_PAYEE);
            score += rules.newPayeeWeight();
        }
        if (rules.isOddHour(hour)) {
            reasons.add(FraudReason.ODD_HOURS);
            score += rules.oddHoursWeight();
        }

        score = Math.min(score, MAX_SCORE);
        Decision decision = decide(score);

        log.info("Fraud score computed | accountId={} decision={} score={} reasons={}",
                command.accountId(), decision, score, reasons);
        return new ScoreResult(decision, score, reasons);
    }

    private Decision decide(int score) {
        if (score >= rules.denyBand()) {
            return Decision.DENY;
        }
        if (score >= rules.reviewBand()) {
            return Decision.REVIEW;
        }
        return Decision.APPROVE;
    }
}
