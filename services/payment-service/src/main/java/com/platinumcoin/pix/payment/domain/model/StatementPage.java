package com.platinumcoin.pix.payment.domain.model;

import java.util.List;

/**
 * One page of the caller's own statement, newest first (step 41) — the {@code api/} edge's view of
 * ledger-service's {@code StatementPage} (step 16), reached through {@code LedgerClient} rather than
 * shared as a type (ADR-0010).
 *
 * @param entries    this page's entries, newest first (empty when the account has no history)
 * @param nextCursor the opaque token for the next page, or {@code null} on the last page
 */
public record StatementPage(List<StatementLine> entries, String nextCursor) {
}
