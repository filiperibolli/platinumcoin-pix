package com.platinumcoin.pix.settlement.api;

import com.platinumcoin.pix.settlement.domain.service.ReconciliationSloAlert;
import com.platinumcoin.pix.settlement.domain.usecase.ScanOutcome;
import com.platinumcoin.pix.settlement.domain.usecase.ScanStuckTransactionsUseCase;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The reconciliation scanner's inbound adapter (step 34): a 60s schedule that drives one
 * {@link ScanStuckTransactionsUseCase} and republishes its result as the {@code reconciliation.oldest.seconds}
 * gauge.
 *
 * <h2>Why this is {@code api/} and calls exactly one use case</h2>
 * A schedule is a way of <i>entering</i> the application (ADR-0011), so the scanner lives beside the queue
 * consumer and the DLQ gauge, and obeys the same rule: hold no policy, call <b>one</b> use case, map the
 * result — here, onto a gauge. Every money decision (which statuses are stuck, how old is too old, which
 * transactions are handed off, how the oldest age is computed) lives in the use case, where a plain-Java
 * test pins it; this class only schedules it and moves the number onto a meter.
 *
 * <h2>Why the gauge reads an {@code AtomicLong} rather than the query</h2>
 * Micrometer gauges are pulled at scrape time. Binding the gauge straight to a scan would put a DynamoDB
 * query on every Prometheus scrape and — worse — couple the metric's cadence to the scraper's. Instead the
 * scheduled scan refreshes an {@code AtomicLong} the gauge reads, exactly the shape
 * {@link SettlementDlqDepthGauge} uses. The tick obeys {@code pix.schedulers.enabled} (off in ITs, which
 * call {@link #scanOnce()} explicitly), since a live scanner against the shared table would fight the tests.
 *
 * <p>{@code reconciliation.oldest.seconds} is the <b>leading</b> indicator of the &lt;5-min reconciliation
 * SLO (ADR-0003): it rises the moment a settlement stalls, well before anything reaches the DLQ, which is
 * why step 44 alerts on it climbing rather than only on a non-zero DLQ depth.
 */
@Component
public class StuckTransactionScanner {

    private static final Logger log = LoggerFactory.getLogger(StuckTransactionScanner.class);

    private final ScanStuckTransactionsUseCase scanStuckTransactions;
    private final ReconciliationSloAlert sloAlert;

    /** Age of the oldest stuck transaction at the last scan; read by the gauge, written by each scan. */
    private final AtomicLong oldestAgeSeconds = new AtomicLong();

    public StuckTransactionScanner(ScanStuckTransactionsUseCase scanStuckTransactions,
            ReconciliationSloAlert sloAlert, MeterRegistry meterRegistry) {
        this.scanStuckTransactions = scanStuckTransactions;
        this.sloAlert = sloAlert;
        Gauge.builder("reconciliation.oldest.seconds", oldestAgeSeconds, AtomicLong::doubleValue)
                .description("Age of the oldest DEBITED/SENT_TO_SPI transaction — the leading indicator of "
                        + "the <5-min reconciliation SLO (step 34, ADR-0003)")
                .baseUnit("seconds")
                .register(meterRegistry);
        log.info("Stuck-transaction scanner ready, it will scan the reconciliation index on a schedule, "
                + "resolve each stuck transaction and report the oldest stuck age as "
                + "reconciliation.oldest.seconds while evaluating the <5-min SLO alert");
    }

    /**
     * One scan. Never lets an exception escape: a scheduled task that throws is noise, and a failed scan
     * must not crash the service — it keeps the last age rather than pretending nothing is stuck, which
     * would silence the very alert this metric exists to raise.
     *
     * @return the scan outcome, so an integration test can drive the scan deterministically instead of
     *         waiting on the schedule
     */
    @Scheduled(fixedDelayString = "${pix.settlement.reconciliation.scan-fixed-delay-ms}")
    public ScanOutcome scanOnce() {
        try {
            ScanOutcome outcome = scanStuckTransactions.execute();
            oldestAgeSeconds.set(outcome.oldestAgeSeconds());
            // Fold the same number the gauge shows into the SLO alert, so a breach fires (and later
            // resolves) off the very figure step 44's Prometheus alert reads — one definition of "late".
            sloAlert.evaluate(outcome.oldestAgeSeconds());
            return outcome;
        } catch (RuntimeException e) {
            log.error("The reconciliation scan tick failed, the oldest-age gauge keeps its last value", e);
            return new ScanOutcome(0, oldestAgeSeconds.get());
        }
    }
}
