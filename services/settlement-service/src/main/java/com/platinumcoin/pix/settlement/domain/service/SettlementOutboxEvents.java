package com.platinumcoin.pix.settlement.domain.service;

import com.platinumcoin.pix.common.event.EventEnvelope;
import com.platinumcoin.pix.common.event.OutboxEvent;
import com.platinumcoin.pix.settlement.domain.model.SettlementConfirmation;
import com.platinumcoin.pix.settlement.domain.usecase.SettlePixCommand;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Mints the {@code PixSettled} an external settlement announces (ADR-0004).
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

    private SettlementOutboxEvents() {
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
