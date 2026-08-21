package com.platinumcoin.pix.ledger.domain.model;

import java.time.Instant;

/**
 * One line of the <b>cold statement archive</b> (ARCHITECTURE §6.10, step 43): a ledger entry as it is
 * written to S3 {@code pix-statement-archive}, once it has aged out of the hot window.
 *
 * <h2>Why this is not {@link LedgerEntry}</h2>
 * Two reasons, and both are about what a file is for.
 * <ul>
 *   <li><b>It carries its account.</b> A {@link LedgerEntry} does not — the account is the DynamoDB
 *       partition key, so the entry is only ever read in a context that already knows it. An archive
 *       object is read on its own, possibly years later by a process (step 53's export) that has only
 *       the file; a line that cannot say whose it is would depend on its own key never being
 *       mis-copied.</li>
 *   <li><b>It carries the description.</b> The statement API composes that field at its edge, so the
 *       hot-path entry record has no need of it; an archive that dropped it would be a statement nobody
 *       can read back — the whole point of the five-year retention.</li>
 * </ul>
 *
 * <p>Money stays <b>signed integer cents</b> (DEBIT negative, CREDIT positive) all the way into the
 * file. The archive is an internal artefact, not an API edge, and decimal formatting is exactly the kind
 * of lossy convenience a five-year record must not carry (domain safety rule 6).
 */
public record ArchivedEntry(
        String accountId,
        String txId,
        Direction direction,
        long amountCents,
        String counterpartAccountId,
        Instant timestamp,
        String entryType,
        String description) {
}
