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
 * that has not yet been resolved/settled. The debtor is the JWT {@code accountId} — there is no
 * source-account field here or on the wire (Domain Safety Rule #1).
 *
 * <p><b>Where the payee lives (step 27):</b> {@code creditorInternal} says whether the destination key
 * resolved inside PlatinumCoin. It is what makes the two shapes of this record legible: an internal
 * send carries {@code creditorInternal=true} with a {@code creditorAccountId} and settles at once,
 * while an external one carries {@code false}, <b>no</b> {@code creditorAccountId} (the payee's account
 * is at another bank), and rests at {@code DEBITED} with the money parked in the clearing account until
 * the asynchronous settlement resolves it.
 *
 * <p><b>Which clearing account the money is parked in (step 33, task 4):</b> {@code clearingAccountId}
 * is the exact account the external debit credited — {@code null} on an internal send, present on an
 * external one. It is persisted (and carried on the {@code PixDebited} event) so a later reversal
 * <i>debits the same account it credited</i>: today that is the single {@code SPI_CLEARING}, but step 52
 * write-shards it into {@code SPI_CLEARING#00..#15}, and a reversal that guessed the shard instead of
 * reading the one used would drain the wrong sub-account and break the per-shard balance.
 *
 * <p><b>Why it came back (step 33):</b> {@code failureReason} is BACEN's refusal reason, written by
 * settlement-service alongside the {@code REVERSED} status. {@code null} on every other state — a
 * successful payment has no reason, and a payment still in flight has no answer yet. It is read here for
 * one purpose: so the status endpoint can tell the payer <i>why</i> their money came back, rather than
 * being less informative than the push that announced it.
 *
 * <p><b>Scored in the path (step 25, classified by ADR-0018):</b> {@code fraudDecision} is the verdict
 * the in-path fraud check returned — {@code APPROVE}/{@code REVIEW} when it ran, {@code SKIPPED} when it
 * could not finish inside the 200ms budget, or {@code FRAUD_ERROR} when it was <i>broken</i> (a refused
 * credential, a drifted contract). {@code fraudSkipped} is the boolean shorthand for <b>"this send went
 * out unscored"</b>, so it is {@code true} for <i>both</i> failure verdicts: the two share one
 * compensating control (the {@code FraudCheckSkipped} outbox event and the async re-score), and it is
 * {@code fraudDecision} that says which of them happened. That is the split worth holding on to — the
 * flag drives <i>behaviour</i>, the verdict drives <i>diagnosis</i>. A {@code DENY} never reaches here: it
 * becomes a {@code 422} before the transaction is written. Both are the durable record that the {@code RECEIVED → FRAUD_CHECKED} stage ran; on a
 * transaction minted before scoring they are {@code null}/{@code false}. The settlement-confirmation
 * fields (step 31) remain deliberately absent.
 */
public record Transaction(
        String txId,
        String endToEndId,
        String debtorAccountId,
        String creditorKey,
        String creditorAccountId,
        boolean creditorInternal,
        String clearingAccountId,
        long amountCents,
        TransactionStatus status,
        String description,
        FraudDecision fraudDecision,
        boolean fraudSkipped,
        Instant createdAt,
        Instant settledAt,
        String failureReason) {
}
