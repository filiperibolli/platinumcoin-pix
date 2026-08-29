package com.platinumcoin.pix.payment.domain.port;

/**
 * The consumer dedup gate (Domain Safety Rule #2), for payment-service's first queue consumer — the
 * statement-export worker of step 53.
 *
 * <p>Same port shape settlement-service and notification-service declare, backed by the same shared
 * {@code pix_processed_events} table (ADR-0006's deliberate one-table exception). The dedup key
 * includes the consumer name, so a fourth consumer joining the platform sees every event it is
 * subscribed to without starving the other three.
 *
 * <p><b>Why an export needs this even though its artifact is idempotent.</b> The two gates answer
 * different questions. The guarded {@code PENDING → READY} transition keeps the <i>bookkeeping</i>
 * single and the fixed object key keeps the <i>artifact</i> single, so a duplicate delivery could not
 * corrupt anything. This gate keeps the <i>work</i> single — a redelivered message does not re-read a
 * two-year range out of object storage to discover it had nothing to do. It is the cheap check in
 * front of the expensive one, and it is the platform's rule for every consumer.
 */
public interface ProcessedEvents {

    /**
     * @return {@code true} if this consumer had never seen the event and now owns it; {@code false} if
     *         it is a duplicate
     */
    boolean claim(String eventId);

    /**
     * Give the claim back, so a redelivery is real work rather than being deduped away. Called
     * whenever the claimed work did not reach a terminal state — a claim held over work that never
     * happened is how an export would sit {@code PENDING} for ever with nothing left to wake it.
     */
    void release(String eventId);
}
