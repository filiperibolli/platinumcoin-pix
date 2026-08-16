package com.platinumcoin.pix.settlement.api;

import com.platinumcoin.pix.settlement.domain.usecase.SettlePixCommand;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The wire shape of a message on {@code settlement-queue} — the broker-agnostic event envelope
 * ({@code common.event.EventEnvelope}) as it arrives.
 *
 * <p><b>Why this record lives in {@code api/}.</b> It is a wire shape, and binding it is exactly what an
 * inbound adapter does — the queue equivalent of a controller's request record (ADR-0011 rule 7: bind,
 * call one use case, map the result). Keeping the Jackson-bound shape here is also what keeps
 * {@code domain/} free of a binding library (ADR-0010).
 *
 * <p><b>The envelope arrives unwrapped.</b> The subscription sets {@code RawMessageDelivery=true}
 * (step 26), so the body is the JSON the publisher wrote, not an SNS notification wrapper with the event
 * escaped inside a {@code Message} field. That is what keeps this consumer broker-agnostic: swapping SNS
 * for Kafka (docs/messaging-kafka-appendix.md) changes the transport, not this record.
 *
 * <p>Unknown fields are tolerated by the auto-configured {@code ObjectMapper} — the payload carries
 * {@code status} and {@code occurredAt} this consumer does not need, and a producer adding a field must
 * never break a consumer that does not read it.
 */
record SettlementMessage(String eventId, String eventType, String correlationId, Payload payload) {

    private static final Logger log = LoggerFactory.getLogger(SettlementMessage.class);

    /**
     * The business facts of a {@code PixDebited}. Money is integer cents, straight off the wire.
     * {@code clearingAccountId} and {@code occurredAt} are added by step 33: the account the debit
     * credited (so a reversal targets it) and the debit instant (so a reversal releases the limit against
     * the right calendar day).
     */
    record Payload(
            String txId,
            String endToEndId,
            String debtorAccountId,
            String creditorKey,
            String clearingAccountId,
            long amountCents,
            String description,
            String occurredAt) {
    }

    boolean isComplete() {
        return eventId != null && eventType != null && payload != null && payload.txId() != null;
    }

    /**
     * Translate the wire shape into the domain's command. Any missing or nonsensical value (a blank id,
     * a non-positive amount) is refused by {@link SettlePixCommand}'s own constructor — a malformed
     * event can therefore never reach the rail, whatever produced it.
     */
    SettlePixCommand toCommand() {
        return new SettlePixCommand(
                eventId,
                payload.txId(),
                payload.endToEndId(),
                payload.debtorAccountId(),
                payload.creditorKey(),
                payload.clearingAccountId(),
                payload.amountCents(),
                payload.description(),
                parseOccurredAt(payload.occurredAt()),
                correlationId);
    }

    /**
     * The event's {@code occurredAt} (the debit instant) as an {@link Instant}, or {@code null} if the
     * event does not carry it or it is unparseable. Not fatal: it only tunes which calendar day a reversal
     * releases the daily limit against, and the use case falls back to its own clock when it is absent.
     */
    private static Instant parseOccurredAt(String occurredAt) {
        if (occurredAt == null || occurredAt.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(occurredAt);
        } catch (DateTimeParseException e) {
            log.warn("PixDebited occurredAt could not be parsed, a reversal will release the daily limit "
                    + "against today instead of the debit day | occurredAt={}", occurredAt);
            return null;
        }
    }
}
