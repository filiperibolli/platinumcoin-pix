package com.platinumcoin.pix.ledger.domain.port;

import com.platinumcoin.pix.ledger.domain.model.ArchivedEntry;
import java.time.YearMonth;
import java.util.List;

/**
 * Outbound port for the cold statement archive (step 43) — S3 {@code pix-statement-archive} in
 * {@code infra/}.
 *
 * <p><b>One object per account and month, rewritten whole.</b> The archive holds <i>derived</i> data:
 * the ledger remains the source of truth, so a month's object is a projection that can always be rebuilt
 * from it. That is precisely why the bucket is a plain one — no versioning, no Object Lock (step 42) —
 * and why a rewrite is the right update primitive: as the hot window rolls forward, the boundary month
 * gains entries and its object is simply written again. Contrast the audit trail, where an overwrite
 * would be a contradiction in terms.
 */
public interface StatementArchive {

    /**
     * Write {@code entries} as the whole content of {@code accountId}'s object for {@code month}.
     *
     * @param entries every archivable entry of that account and month, oldest first; never empty
     * @return the object key written
     */
    String write(String accountId, YearMonth month, List<ArchivedEntry> entries);
}
