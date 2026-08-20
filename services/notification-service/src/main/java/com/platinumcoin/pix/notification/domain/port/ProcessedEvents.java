package com.platinumcoin.pix.notification.domain.port;

/**
 * The consumer dedup gate (Domain Safety Rule #2). Delivery off {@code notification-queue} is
 * at-least-once, so the same {@code eventId} <i>will</i> arrive twice; without this, one payment would
 * be announced to the customer twice.
 *
 * <p>Same port shape settlement-service declares, backed by the same shared
 * {@code pix_processed_events} table — the dedup key includes the consumer name, so settlement,
 * notification and (from Sprint 10) audit each see every event exactly once without starving one
 * another.
 */
public interface ProcessedEvents {

    /**
     * @return {@code true} if this consumer had never seen the event and now owns it; {@code false} if
     *         it is a duplicate that must not be pushed again
     */
    boolean claim(String eventId);

    /**
     * Give the claim back, so a redelivery is real work rather than being deduped away. Called only
     * when the push failed after the claim was taken — the claim means <i>"I am handling this"</i>, and
     * a claim held over work that never happened is how a best-effort consumer loses information
     * silently.
     */
    void release(String eventId);
}
