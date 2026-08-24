package com.platinumcoin.pix.payment.domain.service;

import com.platinumcoin.pix.common.event.EventEnvelope;
import com.platinumcoin.pix.common.event.OutboxEvent;
import com.platinumcoin.pix.common.web.CorrelationId;
import com.platinumcoin.pix.payment.domain.model.Transaction;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Turns an accepted send into the events it announces to the rest of the platform (step 28, ADR-0004).
 *
 * <p><b>Why this is domain and not adapter code.</b> "Which event does this transaction announce" is a
 * business decision with consequences: an internal send that announced {@code PixDebited} would land on
 * the settlement-queue (whose subscription filters exactly that type, step 26) and have
 * settlement-service ask BACEN to settle a Pix that never left the bank. The repository adapter's job
 * is to write items atomically, not to decide what happened.
 *
 * <p><b>The three events.</b>
 * <ul>
 *   <li>{@code PixDebited} — external send: the payer is debited and the money sits in the clearing
 *       account. This is the trigger the settlement flow consumes.</li>
 *   <li>{@code PixSettled} — internal send: the atomic posting <i>was</i> the settlement (step 21), so
 *       the terminal event is emitted immediately. Audit and notification consume it exactly like an
 *       external settlement's, which is the point: the consumers never learn where the payee banks.</li>
 *   <li>{@code FraudCheckSkipped} — rides along, in the same atomic write, whenever the in-path check
 *       failed open (ADR-0005). The skip is a durable fact that async re-scoring picks up, not a log
 *       line that scrolls away. It is emitted for <b>both</b> unscored classes (ADR-0018): a broken
 *       check needs the compensating re-score at least as much as a slow one does, and the event name
 *       stays {@code FraudCheckSkipped} because the consumer's job — "score this one after the fact" —
 *       is identical either way. Which class it was is on the transaction, where a query can ask.</li>
 * </ul>
 *
 * <p>The {@code correlationId} is read from the ambient request context rather than passed in: it is
 * envelope metadata, not a business input, and taking it here guarantees <i>every</i> event carries it.
 * That is what keeps ADR-0012's promise across the asynchronous boundary — the settlement logs minutes
 * later still {@code grep} under the id of the request that caused them.
 */
public final class PixOutboxEvents {

    private static final String PIX_DEBITED = "PixDebited";
    private static final String PIX_SETTLED = "PixSettled";
    private static final String FRAUD_CHECK_SKIPPED = "FraudCheckSkipped";

    private PixOutboxEvents() {
    }

    /**
     * The events a freshly accepted send writes into its outbox, in order: the state event first, then
     * the fail-open marker when the send went out unscored (either failure class). All of them are written in the <b>same</b>
     * {@code TransactWriteItems} as the transaction itself.
     */
    public static List<OutboxEvent> forAcceptedSend(Transaction transaction, Instant occurredAt) {
        List<OutboxEvent> events = new ArrayList<>(2);
        events.add(transaction.creditorInternal()
                ? pixSettled(transaction, occurredAt)
                : pixDebited(transaction, occurredAt));
        if (transaction.fraudSkipped()) {
            events.add(fraudCheckSkipped(transaction, occurredAt));
        }
        return List.copyOf(events);
    }

    private static OutboxEvent pixDebited(Transaction transaction, Instant occurredAt) {
        Map<String, Object> payload = sendPayload(transaction, occurredAt);
        // The exact clearing account this debit credited (step 33, task 4). settlement-service reads it
        // off the event so a reversal debits the same account — no re-derivation that could miss the
        // step-52 shard the money actually went to.
        payload.put("clearingAccountId", transaction.clearingAccountId());
        return event(PIX_DEBITED, payload, occurredAt);
    }

    private static OutboxEvent pixSettled(Transaction transaction, Instant occurredAt) {
        Map<String, Object> payload = sendPayload(transaction, occurredAt);
        payload.put("creditorAccountId", transaction.creditorAccountId());
        payload.put("settledAt", EventEnvelope.timestamp(transaction.settledAt()));
        return event(PIX_SETTLED, payload, occurredAt);
    }

    private static OutboxEvent fraudCheckSkipped(Transaction transaction, Instant occurredAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("txId", transaction.txId());
        payload.put("debtorAccountId", transaction.debtorAccountId());
        payload.put("creditorKey", transaction.creditorKey());
        payload.put("amountCents", transaction.amountCents());
        payload.put("occurredAt", EventEnvelope.timestamp(occurredAt));
        return event(FRAUD_CHECK_SKIPPED, payload, occurredAt);
    }

    /**
     * The facts common to both state events. Money is integer cents — a consumer must never have to
     * parse a decimal string back into money.
     */
    private static Map<String, Object> sendPayload(Transaction transaction, Instant occurredAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("txId", transaction.txId());
        payload.put("endToEndId", transaction.endToEndId());
        payload.put("debtorAccountId", transaction.debtorAccountId());
        payload.put("creditorKey", transaction.creditorKey());
        payload.put("amountCents", transaction.amountCents());
        payload.put("description", transaction.description());
        payload.put("status", transaction.status().name());
        payload.put("occurredAt", EventEnvelope.timestamp(occurredAt));
        return payload;
    }

    /**
     * A fresh {@code eventId} per event — never the txId. Delivery is at-least-once, so this id is what
     * every consumer dedupes on (Domain Safety Rule #2); reusing the transaction id would make two
     * different events about one payment look like a duplicate of each other.
     */
    private static OutboxEvent event(String type, Map<String, Object> payload, Instant occurredAt) {
        return new OutboxEvent(
                "evt-" + UUID.randomUUID(), type, payload, occurredAt, CorrelationId.current());
    }
}
