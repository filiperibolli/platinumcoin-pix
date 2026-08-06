package com.platinumcoin.pix.ledger.domain;

import java.util.List;

/**
 * One page of an account's statement — the ledger entries in newest-first order plus an opaque
 * {@code nextCursor} to resume from (step 16).
 *
 * <p>The cursor is DynamoDB's own pagination token: the base64 of the query's {@code LastEvaluatedKey}
 * (encoded in {@code infra/}, so no AWS type reaches here). It is <b>opaque to the client</b> — never
 * an offset/limit, which DynamoDB does not have — and {@code null} exactly when the last query
 * returned no continuation, i.e. the caller has reached the end of the history.
 *
 * <p>Modelling note: the account whose statement this is is <i>not</i> a field. The caller already
 * named it, every entry lives in that account's partition, and re-stating it here would be a second,
 * weaker source of truth for a fact the request already carries.
 *
 * @param entries    the entries of this page, newest first (empty when the account has no history)
 * @param nextCursor the token to fetch the following page, or {@code null} on the last page
 */
public record StatementPage(List<LedgerEntry> entries, String nextCursor) {
}
