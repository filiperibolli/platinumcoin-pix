package com.platinumcoin.pix.ledger.domain;

import java.time.Instant;

/**
 * One immutable leg of a posting — an {@code sk = ENTRY#<isoTimestamp>#<txId>} item of an
 * {@code ACCOUNT#<accountId>} partition (docs/data-model.md §3). Two entries are written per posting,
 * one on each side, and they are <b>append-only</b>: a mistake is corrected by a compensating
 * posting, never by updating or deleting an entry (domain safety rule 5).
 *
 * <p>Written for the first time in step 14 and queried as a statement in step 16; it exists already
 * because the model is what this step validates against the seeded table.
 *
 * <p>Two modelling notes worth the reading:
 * <ul>
 *   <li>The account the entry belongs to is <b>not</b> a field: it is the partition key of the item.
 *       {@code counterpartAccountId} is the <i>other</i> side — for a DEBIT leg on acc-001 it is who
 *       got the money (a payee, or {@code SPI_CLEARING} when the money is in flight to BACEN).</li>
 *   <li>{@code timestamp} is part of the sort key, not merely a stamp: a timestamp-prefixed sort key
 *       gives chronological ordering for free, which is what makes the newest-first statement a plain
 *       {@code Query} with {@code ScanIndexForward=false} instead of a sort somewhere in memory.</li>
 * </ul>
 *
 * @param amountCents integer cents, <b>signed by {@link Direction}</b> — negative on a DEBIT,
 *                    positive on a CREDIT (see {@link Direction} for why that matters)
 * @param entryType   why the money moved: {@code SEED_FUNDING} today, joined by {@code PIX_OUT},
 *                    {@code PIX_IN}, {@code CLEARING_RELEASE} and the reversal type as steps 21, 27,
 *                    33 and 37 land. An open vocabulary, hence a string and not an enum: an entry
 *                    written by a newer service must never fail to load in an older one.
 */
public record LedgerEntry(
        String txId,
        Direction direction,
        long amountCents,
        String counterpartAccountId,
        Instant timestamp,
        String entryType) {
}
