package com.platinumcoin.pix.payment.domain.usecase;

import com.platinumcoin.pix.payment.domain.model.LimitDecision;
import com.platinumcoin.pix.payment.domain.port.DailyLimitReservation;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * In-memory {@link DailyLimitReservation} for the plain-Java use-case tests. It mirrors the real
 * adapter's reserve/release arithmetic (deny when {@code used + amount > limit}, or when the amount
 * alone exceeds the limit) so the ALLOW/DENY branches are exercised honestly — and it can be told to
 * <b>force</b> a verdict, which is how the {@link LimitDecision#REQUIRE_STEP_UP} seam is driven (the
 * DynamoDB adapter never produces it today, so only a fake can).
 */
final class FakeDailyLimitReservation implements DailyLimitReservation {

    private final Map<String, Long> used = new HashMap<>();
    private LimitDecision forced;
    private int reserveCalls;

    @Override
    public LimitDecision reserve(String accountId, long amountCents, long dailyLimitCents, LocalDate day) {
        reserveCalls++;
        if (forced != null) {
            if (forced == LimitDecision.ALLOW) {
                used.merge(keyOf(accountId, day), amountCents, Long::sum);
            }
            return forced;
        }
        long limitMinusAmount = dailyLimitCents - amountCents;
        if (limitMinusAmount < 0) {
            return LimitDecision.DENY; // the amount alone exceeds the whole limit
        }
        long current = used.getOrDefault(keyOf(accountId, day), 0L);
        if (current <= limitMinusAmount) {
            used.put(keyOf(accountId, day), current + amountCents);
            return LimitDecision.ALLOW;
        }
        return LimitDecision.DENY;
    }

    @Override
    public void release(String accountId, long amountCents, LocalDate day) {
        used.merge(keyOf(accountId, day), -amountCents, Long::sum);
    }

    /** Force every reservation to return {@code decision} (used to drive the REQUIRE_STEP_UP seam). */
    void force(LimitDecision decision) {
        this.forced = decision;
    }

    long usedCents(String accountId, LocalDate day) {
        return used.getOrDefault(keyOf(accountId, day), 0L);
    }

    int reserveCalls() {
        return reserveCalls;
    }

    private static String keyOf(String accountId, LocalDate day) {
        return accountId + "#" + day;
    }
}
