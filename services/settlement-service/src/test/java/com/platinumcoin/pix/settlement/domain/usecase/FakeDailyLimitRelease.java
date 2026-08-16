package com.platinumcoin.pix.settlement.domain.usecase;

import com.platinumcoin.pix.settlement.domain.port.DailyLimitRelease;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The daily-limit counter, recording each release so a test can prove the reversal returned exactly what
 * was reserved, against the debit day, and — crucially — <b>exactly once</b> (release is not idempotent).
 */
final class FakeDailyLimitRelease implements DailyLimitRelease {

    record Release(String accountId, long amountCents, LocalDate day) {
    }

    private final List<String> trace;
    private final List<Release> releases = new ArrayList<>();

    FakeDailyLimitRelease(List<String> trace) {
        this.trace = trace;
    }

    @Override
    public void release(String accountId, long amountCents, LocalDate day) {
        trace.add("dailyLimits.release");
        releases.add(new Release(accountId, amountCents, day));
    }

    List<Release> releases() {
        return releases;
    }
}
