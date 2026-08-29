package com.platinumcoin.pix.ledger.domain.model;

import java.time.Instant;

/**
 * Where the online statement ends and the cold archive begins (step 53).
 *
 * <p>The same fact {@code ArchiveOldEntriesUseCase} computes for itself every run — {@code now -
 * hotWindow} — published so that the rest of the platform can ask instead of configuring a second copy
 * of it. ledger-service owns this boundary because it owns both halves of it: the table the hot side
 * lives in and the job that moves entries to the cold side.
 *
 * @param hotWindowDays the configured window, in days
 * @param coldBefore    entries older than this instant have been copied to the archive
 */
public record StatementWindow(long hotWindowDays, Instant coldBefore) {
}
