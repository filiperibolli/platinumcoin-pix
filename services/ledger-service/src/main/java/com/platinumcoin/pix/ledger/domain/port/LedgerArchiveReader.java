package com.platinumcoin.pix.ledger.domain.port;

import com.platinumcoin.pix.ledger.domain.model.ArchivedEntry;
import java.time.Instant;
import java.util.List;

/**
 * Outbound port for reading the ledger <b>in bulk, for archiving</b> (step 43) — deliberately separate
 * from {@link LedgerRepository} rather than two more methods on it.
 *
 * <h2>Why a second port on the same table</h2>
 * The two have opposite shapes and opposite risks. {@link LedgerRepository} is the money path: single
 * items, strongly consistent reads, one atomic transaction, and the only place in the platform allowed
 * to write an entry. This one is a batch reader: unbounded, eventually consistent, and — the point —
 * <b>it cannot write or delete anything</b>. Splitting them means the archiving job is structurally
 * incapable of touching the ledger, which is a stronger guarantee than a comment saying it does not.
 * It is the same technique settlement-service uses to keep its narrow rights over {@code pix_transactions}
 * separated (ADR-0006).
 *
 * <p>Note what is <b>absent</b> and must stay absent: no {@code delete}, no {@code markArchived}. Ledger
 * history is append-only (domain safety rule 5), and hot data is deliberately never removed locally —
 * see {@code ArchiveOldEntriesUseCase} for the production difference.
 */
public interface LedgerArchiveReader {

    /**
     * The accounts that have a ledger partition, at most {@code limit} of them.
     *
     * <p>There is no index of accounts in {@code pix_ledger} — the balance items <i>are</i> the list —
     * so the adapter pays for a table scan. That is the honest cost of a whole-ledger batch job and the
     * reason it runs on a slow schedule and off the request path; a production job would instead be
     * driven per account from an event or a work queue, which is the same code behind this port.
     */
    List<String> accountIds(int limit);

    /**
     * Every entry of {@code accountId} strictly older than {@code cutoff}, oldest first.
     *
     * <p>Complete rather than paginated <b>on purpose</b>: the caller writes one object per month, and a
     * truncated read would write a truncated month that later runs would never repair (they would read
     * the same first page again). Completeness is what makes the object trustworthy; the price is that
     * one account's cold history must fit in memory, which a streaming writer would remove.
     */
    List<ArchivedEntry> entriesOlderThan(String accountId, Instant cutoff);
}
