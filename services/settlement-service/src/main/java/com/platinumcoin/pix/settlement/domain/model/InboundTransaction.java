package com.platinumcoin.pix.settlement.domain.model;

import java.time.Instant;

/**
 * A Pix <b>received</b> from another participant, as settlement-service records it (step 37). Maps to a
 * {@code TX#<txId> / META} item of {@code pix_transactions} carrying {@code direction=INBOUND} — the same
 * table, the same partition layout and the same outbox mechanics as an outbound send, because the whole
 * point of the mirror is that receiving is not a different kind of thing.
 *
 * <h2>Why {@code txId} is derived from {@code endToEndId} and not generated</h2>
 * {@code txId = "in-" + endToEndId}, so the item's partition key is a pure function of the rail's own id.
 * That single choice buys both idempotency properties this flow needs:
 * <ul>
 *   <li>The credit <b>posting</b> is idempotent, because the ledger's guard is keyed by {@code txId}
 *       (Domain Safety Rule #2) — a redelivered webhook replays it as a no-op.</li>
 *   <li>The <b>dedupe</b> is a strongly-consistent {@code attribute_not_exists(pk)} on the item itself.
 *       Deduping by querying {@code gsi1} ({@code E2E#…}) would be the tempting alternative and a wrong
 *       one: a GSI is <i>eventually</i> consistent, so two concurrent deliveries could both read "no such
 *       transaction" and both credit. Folding the {@code endToEndId} into the partition key turns the
 *       dedupe into a conditional write, which is the only kind of guard that survives a race.</li>
 * </ul>
 *
 * <h2>The fields an inbound transaction has and an outbound one does not (and vice versa)</h2>
 * There is <b>no {@code debtorAccountId}</b>: the payer banks somewhere else, so the debit leg is the
 * clearing account, not a user. What we know about the payer instead is descriptive — {@code payerName}
 * and {@code payerIspb}, carried for the statement line and the notification text, never used to authorize
 * anything. Symmetrically there is no {@code fraudDecision} (nothing was scored: the money is arriving,
 * not leaving) and no daily-limit reservation (limits bound what an account may <i>send</i>).
 *
 * <p>{@code receivedAt} is <b>our</b> clock — the instant the credit committed here. The rail's own instant
 * is not carried on the webhook, and inventing a settlement timestamp from a remote clock would give
 * reconciliation two "truths" to compare that were never the same measurement.
 *
 * <p>Money is an integer {@code long} of cents, like everywhere inside the platform.
 */
public record InboundTransaction(
        String txId,
        String endToEndId,
        String creditorAccountId,
        String creditorKey,
        String clearingAccountId,
        long amountCents,
        String payerName,
        String payerIspb,
        Instant receivedAt) {

    /** The prefix that makes an inbound transaction id a pure function of the rail's {@code endToEndId}. */
    public static final String TX_ID_PREFIX = "in-";

    /**
     * The deterministic transaction id for an inbound {@code endToEndId}. The <b>one</b> place the mapping
     * is expressed, because the ledger posting, the dedupe guard and any later lookup must all agree on it.
     */
    public static String txIdFor(String endToEndId) {
        return TX_ID_PREFIX + endToEndId;
    }
}
