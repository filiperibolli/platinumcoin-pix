package com.platinumcoin.pix.ledger.domain.usecase;

import java.time.Instant;

/**
 * What one run of {@link ArchiveOldEntriesUseCase} did — the numbers the scheduled adapter logs and an
 * integration test asserts on, instead of inferring the run's effect from the bucket.
 *
 * @param accountsScanned  ledger accounts examined this run
 * @param entriesArchived  entries copied to the cold archive (0 when everything is still hot)
 * @param objectsWritten   monthly objects written — one per account and month that had cold entries
 * @param cutoff           the instant that separated hot from cold on this run
 */
public record ArchiveOutcome(
        int accountsScanned,
        int entriesArchived,
        int objectsWritten,
        Instant cutoff) {
}
