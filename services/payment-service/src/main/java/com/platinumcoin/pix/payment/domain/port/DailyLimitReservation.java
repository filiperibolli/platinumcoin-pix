package com.platinumcoin.pix.payment.domain.port;

import com.platinumcoin.pix.payment.domain.model.LimitDecision;
import java.time.LocalDate;

/**
 * Outbound port for the per-account, per-calendar-day usage counter behind the daily-limit check
 * (docs/data-model.md §4, item {@code LIMIT#<accountId>} / {@code DAY#<yyyy-MM-dd>}). The counter is a
 * <b>maintained value with reserve/release semantics</b>, deliberately <i>not</i> a query-and-sum:
 * {@code pix_transactions} has no index by debtor account, so "today's outbound total" is not a
 * supported access pattern — and a counter is what makes {@link #release} well-defined (a rejection
 * or reversal returns exactly what it reserved).
 *
 * <p>The domain declares the two operations; {@code infra/} implements them with a conditional
 * {@code UpdateItem ADD} against DynamoDB (ADR-0010). The {@code day} is passed in rather than read
 * here: computing the calendar day (America/São Paulo) from the clock is a policy decision that lives
 * in the use case, so a test can pin the day boundary via the injected {@code Clock}.
 */
public interface DailyLimitReservation {

    /**
     * Atomically reserve {@code amountCents} against {@code accountId}'s usage for {@code day}, allowed
     * only if it keeps the day's total within {@code dailyLimitCents}. The comparison value
     * ({@code dailyLimitCents - amountCents}) is computed client-side because a DynamoDB condition
     * expression cannot do arithmetic; an amount that alone exceeds the whole limit is denied before
     * the counter is touched.
     *
     * @return {@link LimitDecision#ALLOW} if the reservation was recorded; {@link LimitDecision#DENY}
     *         if it would breach the limit. (The {@link LimitDecision#REQUIRE_STEP_UP} value exists for
     *         the MFA seam and is never produced by this reservation today — ADR-0007.)
     */
    LimitDecision reserve(String accountId, long amountCents, long dailyLimitCents, LocalDate day);

    /**
     * Return a previously reserved {@code amountCents} to {@code accountId}'s headroom for {@code day}
     * ({@code ADD usedCents -:amount}). Called by a <i>later</i> rejection in the flow (fraud deny,
     * insufficient funds — steps 21/25) or a reversal (step 33), so a rejected or reversed send frees
     * exactly what it took. Unconditional: releasing is always safe.
     */
    void release(String accountId, long amountCents, LocalDate day);
}
