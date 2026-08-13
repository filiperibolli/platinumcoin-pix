package com.platinumcoin.pix.settlement.domain.port;

/**
 * Outbound port for the consumer dedup gate that turns SQS's at-least-once delivery into
 * effectively-once processing (ADR-0004, Domain Safety Rule #2).
 *
 * <p><b>Why a port over common-lib's {@code ProcessedEventStore}.</b> That class is an adapter — it
 * speaks DynamoDB, which {@code domain/} may not import (ADR-0010, enforced by
 * {@code SettlementArchitectureTest}). The interface also states the contract in this service's own
 * words: the consumer name is already bound, so a use case can never dedupe against the wrong consumer's
 * records.
 */
public interface ProcessedEvents {

    /**
     * Claim {@code eventId} for this service, atomically. Called <b>before</b> the side effect, because
     * that is the only ordering under which two concurrent deliveries of the same event cannot both
     * proceed.
     *
     * @return {@code true} when this delivery owns the work; {@code false} when the event was already
     *         handled and the caller must skip the side effect. A duplicate is an expected outcome, not
     *         an error.
     */
    boolean claim(String eventId);

    /**
     * Give the claim back because the side effect did not complete, so a redelivery is processed for
     * real instead of being deduped away. Idempotent; never throws for an absent claim.
     */
    void release(String eventId);
}
