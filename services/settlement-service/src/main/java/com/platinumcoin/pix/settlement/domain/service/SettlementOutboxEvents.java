package com.platinumcoin.pix.settlement.domain.service;

import com.platinumcoin.pix.common.event.EventEnvelope;
import com.platinumcoin.pix.common.event.OutboxEvent;
import com.platinumcoin.pix.settlement.domain.model.InboundTransaction;
import com.platinumcoin.pix.settlement.domain.model.SettlementConfirmation;
import com.platinumcoin.pix.settlement.domain.usecase.SettlePixCommand;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Mints the events settlement-service announces (ADR-0004): {@code PixSettled} on confirmation and
 * {@code PixReversed} on a permanent refusal (step 33), plus {@code PixReceived} for a Pix that arrived
 * from another participant (step 37).
 *
 * <p><b>The same event name payment-service emits for an internal send, on purpose.</b> An internal Pix
 * settles inside the atomic ledger posting and announces {@code PixSettled} immediately (step 21); an
 * external one announces it here, seconds later, once BACEN confirmed. Consumers — notification
 * (Sprint 8), audit (Sprint 10) — must not have to learn where the payee banks in order to know a
 * payment completed, so the event type is identical and the payload differs only in the facts that
 * genuinely differ: an internal settlement carries {@code creditorAccountId} (the payee's account
 * here), an external one carries {@code creditorIspb} (the participant that received the money).
 *
 * <p>A <b>fresh</b> {@code eventId} per event, never the id of the {@code PixDebited} being consumed:
 * that id is what every downstream consumer dedupes on, and reusing it would make two different facts
 * about one payment look like a duplicate of each other.
 */
public final class SettlementOutboxEvents {

    private static final String PIX_SETTLED = "PixSettled";
    private static final String PIX_REVERSED = "PixReversed";
    private static final String PIX_RECEIVED = "PixReceived";

    private SettlementOutboxEvents() {
    }

    /**
     * Mints the {@code PixReceived} an inbound Pix announces (step 37) — the third of the three
     * user-facing outcomes the {@code notification-queue} subscription filters for (step 36), alongside
     * {@code PixSettled} and {@code PixReversed}.
     *
     * <p><b>{@code creditorAccountId} is the routing field, and it is not optional here.</b> The
     * notification flow (step 38/39) has to answer "whose stream does this go to?" from the payload alone;
     * for an outbound {@code PixSettled} that is the debtor, for this event it is the payee. An event that
     * named only the Pix key would force every consumer to re-resolve the directory just to find a user —
     * a synchronous lookup inside an asynchronous fan-out, for a fact we already knew when we credited.
     *
     * <p>Written in the same {@code TransactWriteItems} as the transaction it announces, so a received
     * payment and its announcement are one commit; a fresh {@code eventId}, like every event, because that
     * is what downstream consumers dedupe on.
     *
     * @param occurredAt when this service recorded the credit — the outbox sort key, our clock
     */
    public static OutboxEvent pixReceived(InboundTransaction transaction, String correlationId,
            Instant occurredAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("txId", transaction.txId());
        payload.put("endToEndId", transaction.endToEndId());
        payload.put("direction", "INBOUND");
        // Who to credit, and therefore who to notify — see the note above.
        payload.put("creditorAccountId", transaction.creditorAccountId());
        payload.put("creditorKey", transaction.creditorKey());
        // Integer cents, like every other payload — the money that arrived.
        payload.put("amountCents", transaction.amountCents());
        payload.put("status", "RECEIVED_SETTLED");
        payload.put("receivedAt", EventEnvelope.timestamp(transaction.receivedAt()));
        payload.put("occurredAt", EventEnvelope.timestamp(occurredAt));
        // Descriptive only: the counterpart line on a statement and the "you received R$ X from …" text.
        if (transaction.payerName() != null) {
            payload.put("payerName", transaction.payerName());
        }
        if (transaction.payerIspb() != null) {
            payload.put("payerIspb", transaction.payerIspb());
        }

        return new OutboxEvent("evt-" + UUID.randomUUID(), PIX_RECEIVED, payload, occurredAt,
                correlationId);
    }

    /**
     * Mints the {@code PixReversed} a permanent BACEN refusal announces (step 33): the compensating
     * posting returned the money to the payer, and this event tells the rest of the platform so — the
     * notification flow (Sprint 8) informs the user, the audit trail (Sprint 10) records it. The
     * external-status vocabulary of step 22 already names {@code REVERSED}, so this closes the failure
     * branch of the funnel.
     *
     * <p>A <b>fresh</b> {@code eventId}, like every event: it is what downstream consumers dedupe on, and
     * it is written in the same atomic transaction as the {@code REVERSED} status, so a reversed payment
     * and its announcement are one commit.
     *
     * @param failureReason BACEN's machine-readable refusal reason (e.g. {@code CREDITOR_KEY_NOT_IN_DICT})
     * @param occurredAt    when this service recorded the reversal — the outbox sort key, our clock
     */
    public static OutboxEvent pixReversed(SettlePixCommand command, String failureReason,
            Instant occurredAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("txId", command.txId());
        payload.put("endToEndId", command.endToEndId());
        payload.put("debtorAccountId", command.debtorAccountId());
        payload.put("creditorKey", command.creditorKey());
        // Integer cents, like every other payload — the money that was returned to the payer.
        payload.put("amountCents", command.amountCents());
        payload.put("description", command.description());
        payload.put("status", "REVERSED");
        payload.put("failureReason", failureReason);
        payload.put("occurredAt", EventEnvelope.timestamp(occurredAt));

        return new OutboxEvent("evt-" + UUID.randomUUID(), PIX_REVERSED, payload, occurredAt,
                command.correlationId());
    }

    /**
     * @param occurredAt when <i>this service</i> recorded the settlement — the outbox sort key, so it is
     *                   our clock; the money's own instant is {@code settledAt} inside the payload
     */
    public static OutboxEvent pixSettled(SettlePixCommand command, SettlementConfirmation confirmation,
            Instant occurredAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("txId", command.txId());
        payload.put("endToEndId", command.endToEndId());
        payload.put("debtorAccountId", command.debtorAccountId());
        payload.put("creditorKey", command.creditorKey());
        // Integer cents, like every other payload in the platform: a consumer must never have to parse
        // a decimal string back into money.
        payload.put("amountCents", command.amountCents());
        payload.put("description", command.description());
        payload.put("status", "SETTLED");
        payload.put("settledAt", EventEnvelope.timestamp(confirmation.settledAt()));
        payload.put("occurredAt", EventEnvelope.timestamp(occurredAt));
        if (confirmation.creditorIspb() != null) {
            payload.put("creditorIspb", confirmation.creditorIspb());
        }

        return new OutboxEvent("evt-" + UUID.randomUUID(), PIX_SETTLED, payload, occurredAt,
                command.correlationId());
    }
}
