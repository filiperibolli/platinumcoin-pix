package com.platinumcoin.pix.settlement.domain.model;

import java.time.Duration;
import java.time.Instant;

/**
 * One transaction the reconciliation scan found <b>stuck</b> — sitting in {@link TransactionStatus#DEBITED}
 * or {@link TransactionStatus#SENT_TO_SPI} for longer than the stuck threshold (step 34). It is the unit
 * the scan hands to the reconciliation path (the step-35 resolver).
 *
 * <p><b>Deliberately minimal</b> ({@code txId}, {@code status}, {@code updatedAt}). This step only needs to
 * <i>find</i> a stuck transaction, age it for the {@code reconciliation.oldest.seconds} metric, and hand it
 * off; it does not resolve it. Step 35 enriches the GSI2 projection with the fields a resolver needs to
 * re-drive a settlement ({@code endToEndId}, {@code amountCents}, …) when it needs them — building them here
 * ahead of a reader would be surface nothing yet consumes.
 *
 * @param txId      the transaction's identity ({@code TX#<txId>})
 * @param status    the stuck status it was found under — {@code DEBITED} or {@code SENT_TO_SPI}
 * @param updatedAt the instant its status last changed (the GSI2 sort key), from which its age is measured
 */
public record StuckTransaction(String txId, TransactionStatus status, Instant updatedAt) {

    public StuckTransaction {
        if (txId == null || txId.isBlank()) {
            throw new IllegalArgumentException("txId is required");
        }
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
        if (updatedAt == null) {
            throw new IllegalArgumentException("updatedAt is required");
        }
    }

    /**
     * How long this transaction has sat in its current status as of {@code now} — the leading indicator of
     * the &lt;5-min reconciliation SLO (ADR-0003). Never negative: a clock skew that puts {@code updatedAt}
     * slightly in the future reads as age zero rather than a nonsensical negative age.
     */
    public Duration ageAt(Instant now) {
        Duration age = Duration.between(updatedAt, now);
        return age.isNegative() ? Duration.ZERO : age;
    }
}
