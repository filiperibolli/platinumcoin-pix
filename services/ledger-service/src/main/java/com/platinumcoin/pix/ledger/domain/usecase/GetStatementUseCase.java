package com.platinumcoin.pix.ledger.domain.usecase;

import com.platinumcoin.pix.ledger.domain.model.StatementPage;
import com.platinumcoin.pix.ledger.domain.port.LedgerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Read one page of a ledger account's statement — the entries newest first, with an opaque cursor
 * (ADR-0011: one use case per inbound operation, so {@code ls domain/usecase/} lists this service's
 * capabilities — balance, posting, and now the statement).
 *
 * <p>The one piece of policy that lives here rather than at the edge is the {@code limit}: the
 * controller passes whatever the client sent (possibly nothing), and this use case decides what a
 * page is — {@link #DEFAULT_LIMIT} when unspecified, {@link #MAX_LIMIT} as the ceiling, at least one.
 * That is a business rule about how much history to hand out per call, so per ADR-0011 it belongs in
 * the use case and not in the request binding. The cursor itself stays opaque all the way down: only
 * the adapter decodes it, because only the adapter can (it is an AWS key), and only the adapter can
 * enforce that it belongs to this account.
 *
 * <p>Deliberately <b>not</b> account-scoped, exactly like {@link GetBalanceUseCase}: the account comes
 * from the caller, not from a token. This is the internal seam (ADR-0006) that payment-service's
 * public statement API (step 41) proxies; the "debited account comes from the JWT" rule binds the
 * money-moving endpoint, and nothing here moves money.
 */
public class GetStatementUseCase {

    private static final Logger log = LoggerFactory.getLogger(GetStatementUseCase.class);

    /** Page size when the caller specifies none. */
    static final int DEFAULT_LIMIT = 20;

    /** Hard ceiling on a page, so one call can never scan an unbounded slice of a partition. */
    static final int MAX_LIMIT = 100;

    private final LedgerRepository ledger;

    public GetStatementUseCase(LedgerRepository ledger) {
        this.ledger = ledger;
    }

    /**
     * @param accountId    the account whose statement is read
     * @param cursor       an opaque continuation token, or {@code null}/blank for the first page
     * @param requestedLimit the client's requested page size, or {@code null} for the default
     */
    public StatementPage execute(String accountId, String cursor, Integer requestedLimit) {
        int limit = clampLimit(requestedLimit);
        log.info("Statement page requested for a ledger account | accountId={} requestedLimit={} "
                        + "effectiveLimit={} hasCursor={}",
                accountId, requestedLimit, limit, cursor != null && !cursor.isBlank());

        StatementPage page = ledger.getEntries(accountId, cursor, limit);

        log.info("Statement page resolved from the ledger | accountId={} entries={} hasNextPage={}",
                accountId, page.entries().size(), page.nextCursor() != null);
        return page;
    }

    /**
     * Default when absent, capped at {@link #MAX_LIMIT}, floored at 1 — a {@code limit} of 0 or a
     * negative number is a nonsensical page size, not a request for "everything", so it is coerced to
     * a single entry rather than passed to DynamoDB (which rejects a non-positive {@code Limit}).
     */
    private static int clampLimit(Integer requestedLimit) {
        if (requestedLimit == null) {
            return DEFAULT_LIMIT;
        }
        return Math.max(1, Math.min(requestedLimit, MAX_LIMIT));
    }
}
