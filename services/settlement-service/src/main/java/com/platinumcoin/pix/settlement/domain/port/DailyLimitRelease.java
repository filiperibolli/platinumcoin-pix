package com.platinumcoin.pix.settlement.domain.port;

import java.time.LocalDate;

/**
 * Outbound port to give back the daily-limit headroom a reversed send had reserved (step 33). When an
 * external send was accepted, payment-service reserved {@code amountCents} against the payer's calendar
 * day (step 20); a permanent refusal means that money never left, so the reservation must be returned or
 * the payer would be blocked from re-sending it.
 *
 * <p><b>Why settlement writes this counter directly.</b> The {@code LIMIT#<account>/DAY#<day>} item lives
 * in {@code pix_transactions}, the table settlement already writes under ADR-0006's documented exception.
 * An internal API to payment-service just to decrement a counter would add a network hop and a new
 * endpoint for no guarantee the outbox does not already provide; a single scoped {@code ADD} is simpler
 * and stays within the same exception.
 *
 * <p><b>Release is not idempotent</b> ({@code ADD usedCents -:amount} run twice double-refunds), so the
 * use case calls it exactly once — only when the guarded {@code SENT_TO_SPI → REVERSED} transition wins
 * on this invocation. A redelivery finds the transaction already {@code REVERSED}, the transition
 * refuses, and the release is skipped. The residual risk (a crash between the transition and this call)
 * leaves the reservation standing — an over-count that never overspends and self-heals when the day's
 * counter expires, the same conservative edge ADR-0007/step 20 already accepts.
 */
public interface DailyLimitRelease {

    /**
     * Return {@code amountCents} of headroom to the payer's counter for {@code day} — the calendar day
     * the original reservation was made against (the debit instant in America/São_Paulo), not the day the
     * reversal happens.
     */
    void release(String accountId, long amountCents, LocalDate day);
}
