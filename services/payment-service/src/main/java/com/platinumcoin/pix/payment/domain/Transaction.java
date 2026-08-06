package com.platinumcoin.pix.payment.domain;

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
 * <p><b>What is deliberately absent in the skeleton:</b> the creditor's resolved account and the
 * {@code creditorInternal} flag (step 21 resolves the key), the fraud verdict (step 25) and the
 * settlement fields (steps 27/31). {@code creditorKey} holds the raw destination key exactly as the
 * client sent it. The debtor is the JWT {@code accountId} — there is no source-account field here or
 * on the wire (Domain Safety Rule #1).
 */
public record Transaction(
        String txId,
        String endToEndId,
        String debtorAccountId,
        String creditorKey,
        long amountCents,
        TransactionStatus status,
        String description,
        Instant createdAt) {
}
