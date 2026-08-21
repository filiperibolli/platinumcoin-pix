package com.platinumcoin.pix.settlement.domain.model;

/**
 * One event on its way into the immutable audit trail (ARCHITECTURE §6.10) — the platform's long-term
 * event store, and the SNS/SQS analogue of Kafka's replayable log
 * ({@code docs/messaging-kafka-appendix.md}).
 *
 * <p><b>The line is the event, verbatim.</b> {@code json} is the envelope exactly as the publisher
 * wrote it, merely compacted to a single line so a JSONL object stays readable line by line. Nothing is
 * re-shaped, re-named or enriched on the way in, and that is deliberate: an audit trail records what
 * happened, so a field this platform does not understand today must still be in the file the day
 * someone needs it. Everything else here is metadata <i>about</i> the delivery, read from the same
 * envelope: {@code eventId} is the identity the batch dedupes on, {@code eventType} and
 * {@code correlationId} exist so a log line naming this write can be grepped.
 *
 * <p><b>{@code ackToken} is deliberately opaque.</b> It is the broker's receipt handle, and the domain
 * treats it as a black box it hands back untouched in {@code AuditFlushOutcome} once the line is
 * durable. That is what lets the use case own the rule "acknowledge only what has been written" without
 * {@code domain/} ever learning that the broker is SQS (ADR-0010).
 *
 * @param eventId       the envelope's id — the dedup key inside a batch; required
 * @param eventType     PascalCase past tense (`PixDebited`, `PixSettled`, …); may be absent on a
 *                      malformed producer's event, which is recorded anyway rather than dropped
 * @param correlationId the causing request's id, for the log line; may be absent
 * @param json          the event envelope as published, compacted to one line; required
 * @param ackToken      opaque acknowledgement handle, returned once the line is durable; required
 */
public record AuditEvent(
        String eventId,
        String eventType,
        String correlationId,
        String json,
        String ackToken) {

    public AuditEvent {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("An audit event needs an eventId to be deduped by.");
        }
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("An audit event needs the event JSON to record.");
        }
        if (ackToken == null || ackToken.isBlank()) {
            throw new IllegalArgumentException("An audit event needs an ackToken to acknowledge with.");
        }
    }
}
