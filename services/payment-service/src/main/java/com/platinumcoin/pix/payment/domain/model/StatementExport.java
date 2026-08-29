package com.platinumcoin.pix.payment.domain.model;

import java.time.Instant;

/**
 * A cold-statement export request, as it lives in {@code pix_transactions} under
 * {@code EXPORT#<exportId> / META} (step 53, docs/data-model.md §4).
 *
 * <h2>Why the id is derived and not random</h2>
 * {@code exportId} is a deterministic function of the account and the {@code Idempotency-Key}
 * ({@code StatementExportId}), which is what lets the <b>conditional put of this very item</b> be the
 * idempotency claim: a replay computes the same id, collides with the item already there, and is
 * answered from it. There is no second store to keep in step, and no window in which a request has been
 * claimed but its resource does not exist yet.
 *
 * <p>{@code requestHash} is what separates the two things a replay can mean. The same key with the same
 * range is a retry and replays the original answer; the same key with a <i>different</i> range is a
 * client bug and gets {@code 409 IDEMPOTENCY_KEY_REUSED}. Without the hash on the item those two would
 * be indistinguishable, because the id alone does not remember what was asked for.
 *
 * @param exportId      derived id, {@code exp-<32 hex>}
 * @param accountId     the owner — every read of this export is checked against the JWT's account
 * @param range         the months asked for
 * @param status        where in the lifecycle it is
 * @param requestHash   fingerprint of the requested range, for the key-reuse check
 * @param requestedAt   when the request was accepted
 * @param downloadKey   object key of the CSV artifact; {@code null} until {@code READY}. The <b>key</b>
 *                      and not a URL: a presigned URL expires, and an export whose only handle expired
 *                      would be permanently undownloadable. The key is signed afresh on every read.
 * @param completedAt   when it reached a terminal state; {@code null} while {@code PENDING}
 * @param failureReason why it failed; {@code null} unless {@code FAILED}
 */
public record StatementExport(
        String exportId,
        String accountId,
        MonthRange range,
        StatementExportStatus status,
        String requestHash,
        Instant requestedAt,
        String downloadKey,
        Instant completedAt,
        String failureReason) {

    /** A freshly accepted request: PENDING, with nothing produced yet. */
    public static StatementExport pending(
            String exportId, String accountId, MonthRange range, String requestHash, Instant requestedAt) {
        return new StatementExport(
                exportId, accountId, range, StatementExportStatus.PENDING, requestHash, requestedAt,
                null, null, null);
    }

    public boolean isTerminal() {
        return status != StatementExportStatus.PENDING;
    }
}
