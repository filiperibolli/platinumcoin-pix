package com.platinumcoin.pix.payment.domain.model;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;

/**
 * Where the online statement ends and the cold archive begins, as ledger-service reports it (step 53).
 *
 * <h2>Why payment-service asks instead of configuring it</h2>
 * The hot window is ledger-service's property: it owns {@code pix_ledger}, it runs the archiving job,
 * and {@code pix.archive.hot-window-days} is the dial that decides what that job copies. payment-service
 * needs the same boundary to answer {@code 422 USE_HOT_STATEMENT}, and the tempting shortcut — give it
 * the same environment variable — would put one policy constant in two services. Step 52 recorded what
 * that costs: two definitions of {@code CRC32(txId) % N} would have been "how money lands in a
 * sub-account nobody compensates". The failure here is smaller (an export refused for months that are
 * in fact cold, or accepted for months that are still hot and exports as empty) but it is the same
 * shape, and it appears only after someone changes the window on one side.
 *
 * <p>The cost is honest: one internal call on the export-request path, and payment-service refusing
 * exports when ledger-service cannot be reached — which is the correct direction, since it cannot know
 * what is exportable without it.
 *
 * @param hotWindowDays how many days of statement stay online — reported for logs and diagnostics
 * @param coldBefore    entries older than this instant are in the archive
 */
public record StatementWindow(long hotWindowDays, Instant coldBefore) {

    /**
     * The most recent month the archive can hold anything for.
     *
     * <p>It is the month <b>containing</b> the cutoff, not the month before it: the cutoff falls
     * mid-month, so the earlier part of that month is already older than the window and has been
     * archived. Treating the cutoff month as fully hot would refuse an export of the very month a
     * customer is most likely to want the boundary of — the off-by-one this method exists to name.
     */
    public YearMonth newestColdMonth() {
        return YearMonth.from(coldBefore.atZone(ZoneOffset.UTC));
    }
}
