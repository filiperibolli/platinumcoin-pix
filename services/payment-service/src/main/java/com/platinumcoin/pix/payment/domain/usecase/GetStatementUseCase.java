package com.platinumcoin.pix.payment.domain.usecase;

import com.platinumcoin.pix.payment.domain.model.StatementPage;
import com.platinumcoin.pix.payment.domain.port.LedgerClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Read one page of the caller's own statement (step 41, ADR-0011: one use case per inbound operation).
 * Proxies ledger-service's internal statement seam (step 16) through {@link LedgerClient}: no cache in
 * front of this read (unlike {@link GetBalanceUseCase}), because a statement page is paginated history,
 * not a single hot value re-read on every screen.
 *
 * <p>The one piece of policy this use case owns is {@code limit}: {@link #DEFAULT_LIMIT} when the
 * client sent none, {@link #MAX_LIMIT} as the ceiling, floored at one. This mirrors ledger-service's own
 * clamp exactly, and deliberately does not trust it — the public contract
 * ({@code docs/api/openapi.yaml}) is this service's promise to its callers, so payment-service enforces
 * it at its own boundary rather than assuming an internal collaborator always will. The cursor is never
 * touched here: it stays opaque all the way to the ledger, which is the only party able to decode and
 * validate it (step 16).
 */
public class GetStatementUseCase {

    private static final Logger log = LoggerFactory.getLogger(GetStatementUseCase.class);

    /** Page size when the caller specifies none, matching {@code docs/api/openapi.yaml}. */
    static final int DEFAULT_LIMIT = 20;

    /** Hard ceiling on a page, matching {@code docs/api/openapi.yaml}. */
    static final int MAX_LIMIT = 100;

    private final LedgerClient ledger;

    public GetStatementUseCase(LedgerClient ledger) {
        this.ledger = ledger;
    }

    /**
     * @param accountId      the caller's own account, from the JWT — never a client-supplied value
     * @param cursor         an opaque continuation token, or {@code null}/blank for the first page
     * @param requestedLimit the client's requested page size, or {@code null} for the default
     */
    public StatementPage execute(String accountId, String cursor, Integer requestedLimit) {
        int limit = clampLimit(requestedLimit);
        log.info("Statement page requested by the account's owner | accountId={} requestedLimit={} "
                        + "effectiveLimit={} hasCursor={}",
                accountId, requestedLimit, limit, cursor != null && !cursor.isBlank());

        StatementPage page = ledger.readStatement(accountId, cursor, limit);

        log.info("Statement page served through the ledger seam | accountId={} entries={} "
                        + "hasNextPage={}",
                accountId, page.entries().size(), page.nextCursor() != null);
        return page;
    }

    /**
     * Default when absent, capped at {@link #MAX_LIMIT}, floored at 1 — a {@code limit} of 0 or a
     * negative number is a nonsensical page size, not a request for "everything".
     */
    private static int clampLimit(Integer requestedLimit) {
        if (requestedLimit == null) {
            return DEFAULT_LIMIT;
        }
        return Math.max(1, Math.min(requestedLimit, MAX_LIMIT));
    }
}
