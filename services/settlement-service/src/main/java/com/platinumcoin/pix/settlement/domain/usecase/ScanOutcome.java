package com.platinumcoin.pix.settlement.domain.usecase;

/**
 * What one reconciliation scan found (step 34): how many stuck transactions were handed to the
 * reconciliation path, and the age in seconds of the oldest of them — the value the
 * {@code pix.reconciliation.oldest.seconds} gauge reports.
 *
 * <p>The use case returns this rather than touching a meter itself: Micrometer is a framework type and the
 * domain stays plain Java (ADR-0010), so the {@code api/} scanner reads {@code oldestAgeSeconds} onto the
 * gauge. {@code oldestAgeSeconds} is {@code 0} when nothing is stuck — a clean floor the alert on this
 * metric reads as "reconciliation has no backlog".
 *
 * @param found            how many stuck transactions were found and handed off this scan
 * @param oldestAgeSeconds the age, in seconds, of the oldest stuck transaction, or {@code 0} when none
 */
public record ScanOutcome(int found, long oldestAgeSeconds) {

    /** An empty scan: nothing stuck, so the oldest-age floor is zero. */
    static final ScanOutcome EMPTY = new ScanOutcome(0, 0L);
}
