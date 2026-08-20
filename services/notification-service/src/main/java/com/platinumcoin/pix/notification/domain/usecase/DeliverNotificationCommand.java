package com.platinumcoin.pix.notification.domain.usecase;

import java.time.Instant;

/**
 * One event off {@code notification-queue}, in the domain's own terms — the inbound adapter has already
 * bound the wire shape and pulled out the fields that carry meaning here.
 *
 * <p><b>Named fields, not the payload map (step 39).</b> Step 38 forwarded the source payload verbatim
 * because the push was still the raw event; now the domain has to decide what a customer <i>sees</i>, and
 * a decision made by digging keys out of a {@code Map} inside {@code domain/} is a decision made against
 * a JSON shape this service does not own. Binding happens once, in {@code api/}; below this line the
 * platform's facts have names. The record is wide on purpose — every field is a fact one of the three
 * event types actually carries, and most are {@code null} for the other two.
 *
 * <p><b>Both account fields, either of which may be null.</b> Which one is the addressee depends on the
 * event type ({@link com.platinumcoin.pix.notification.domain.service.NotificationRouting}), and the
 * command deliberately carries both rather than a pre-resolved "recipient": the routing rule is policy
 * and belongs in the domain, not in the adapter that parsed the JSON.
 *
 * <p>{@code amountCents} is a {@code long} — integer cents end to end (Domain Safety Rule #6), even on
 * a path that only reports on money.
 *
 * <p><b>Three instants, because three events answer "when" differently</b> and picking between them is
 * policy, not parsing: {@code settledAt} is BACEN's confirmation, {@code receivedAt} is when an inbound
 * Pix arrived, and {@code occurredAt} is when <i>we</i> recorded the outcome — the fallback that is
 * always present. {@link com.platinumcoin.pix.notification.domain.service.NotificationVocabulary}
 * chooses; the adapter must not.
 *
 * @param creditorKey   the Pix key the money was sent to — the counterpart display of an outbound outcome
 * @param payerName     who sent an inbound Pix (descriptive, optional on the wire)
 * @param payerIspb     the participant that sent it, the fallback display when no name travelled
 * @param failureReason BACEN's machine-readable refusal reason; only a reversal has one
 */
public record DeliverNotificationCommand(
        String eventId,
        String eventType,
        String correlationId,
        String txId,
        String debtorAccountId,
        String creditorAccountId,
        long amountCents,
        String creditorKey,
        String payerName,
        String payerIspb,
        String failureReason,
        Instant settledAt,
        Instant receivedAt,
        Instant occurredAt) {

    public DeliverNotificationCommand {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId is required — it is the dedup key");
        }
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType is required — it decides the addressee");
        }
    }
}
