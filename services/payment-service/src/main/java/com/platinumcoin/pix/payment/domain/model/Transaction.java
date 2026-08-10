package com.platinumcoin.pix.payment.domain.model;

import java.time.Instant;

/**
 * A send-Pix transaction as this service knows it. Maps to the {@code TX#<txId> / META} item of
 * {@code pix_transactions} (docs/data-model.md §4); the adapter derives the index attributes
 * ({@code gsi1pk = E2E#<endToEndId>} for the reconciliation/inbound-dedup lookup,
 * {@code gsi2pk = STATUS#<status>} + {@code gsi2sk = updatedAt} for the stuck-transaction scan) from
 * these fields, so the item is index-consistent from the moment it is written.
 *
 * <p>Money is an integer {@code long} of cents end to end — it is only ever formatted to a decimal
 * string at the {@code api/} edge. {@code amountCents} is guaranteed strictly positive by
 * {@link Money#toCents(String)} before a {@code Transaction} is ever constructed.
 *
 * <p><b>Resolved on the internal path (step 21):</b> {@code creditorAccountId} is account-service's
 * DICT answer for {@code creditorKey} (the raw destination key as the client sent it), and
 * {@code settledAt} is stamped when the atomic ledger posting commits — for an internal send that is
 * the same instant the money moves. Both are {@code null} on a freshly {@code RECEIVED} transaction
 * that has not yet been resolved/settled. The fraud verdict (step 25) and the external settlement
 * fields (steps 27/31) remain deliberately absent. The debtor is the JWT {@code accountId} — there is
 * no source-account field here or on the wire (Domain Safety Rule #1).
 */
public record Transaction(
        String txId,
        String endToEndId,
        String debtorAccountId,
        String creditorKey,
        String creditorAccountId,
        long amountCents,
        TransactionStatus status,
        String description,
        Instant createdAt,
        Instant settledAt) {
}
