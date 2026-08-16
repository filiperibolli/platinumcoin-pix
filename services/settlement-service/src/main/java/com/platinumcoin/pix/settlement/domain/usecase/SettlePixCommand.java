package com.platinumcoin.pix.settlement.domain.usecase;

import java.time.Instant;
import java.util.Objects;

/**
 * One {@code PixDebited} delivery, as the domain sees it — the queue message already unwrapped from its
 * envelope by the inbound adapter.
 *
 * <p><b>Event-carried state transfer: these facts are trusted, and here is why.</b> The payload was
 * written by payment-service in the <i>same</i> {@code TransactWriteItems} as the transaction it
 * describes (step 28), so it cannot describe a payment that never committed. Re-reading the item before
 * settling would cost a read per message and buy nothing: the state check that actually matters is the
 * guarded transition, and that one lives inside the write, where a read-then-check could never be safe
 * anyway.
 *
 * <p>{@code amountCents} is a {@code long} of integer cents from the queue to the rail — no decimal
 * string, no {@code double}, no conversion anywhere in between.
 *
 * @param clearingAccountId the exact clearing account the acceptance-time debit credited (step 33 task
 *                          4). Carried on the event so a reversal debits the same account it credited —
 *                          the same shard, once step 52 shards it — rather than re-deriving it and risking
 *                          the wrong sub-account. Required: every external {@code PixDebited} carries it.
 * @param debitedAt         the instant the payer was debited (the event's {@code occurredAt}); the daily
 *                          limit was reserved against this instant's calendar day, so a reversal releases
 *                          it against the same day. May be absent on a hand-built or legacy event.
 * @param eventId       the de-dup key of the delivery being processed (Domain Safety Rule #2)
 * @param correlationId the request that caused the payment, carried across the asynchronous boundary so
 *                      one {@code grep} still reconstructs the whole path (ADR-0012); may be absent
 */
public record SettlePixCommand(
        String eventId,
        String txId,
        String endToEndId,
        String debtorAccountId,
        String creditorKey,
        String clearingAccountId,
        long amountCents,
        String description,
        Instant debitedAt,
        String correlationId) {

    public SettlePixCommand {
        requireText(eventId, "eventId");
        requireText(txId, "txId");
        requireText(endToEndId, "endToEndId");
        requireText(debtorAccountId, "debtorAccountId");
        requireText(creditorKey, "creditorKey");
        requireText(clearingAccountId, "clearingAccountId");
        if (amountCents <= 0) {
            // A non-positive amount is not money. Refusing it here means a malformed event can never
            // reach the rail, whatever produced it.
            throw new IllegalArgumentException("amountCents must be strictly positive");
        }
        description = Objects.requireNonNullElse(description, "");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
