package com.platinumcoin.pix.payment.domain.port;

import com.platinumcoin.pix.payment.domain.model.PendingOutboxEvent;
import java.util.List;

/**
 * Outbound port for the publisher's side of the outbox (step 29, ADR-0004): read what is waiting, and
 * flag what has gone out. The write side is {@link TransactionRepository#create}, which puts the events
 * there in the same transaction as the state they describe — two ports on the same table because they
 * answer to two different concerns (the guarantee vs. the delivery), and only the delivery half is
 * allowed to be retried.
 */
public interface OutboxEventStore {

    /**
     * The events still waiting to be published, <b>oldest first</b>, at most {@code limit} of them.
     *
     * <p>The adapter reads them off a sparse index that only ever holds in-flight events, so this is
     * O(unpublished) and never O(history) — the reason a 1s poll is affordable at all. Oldest-first is
     * the index's sort order, and it is what keeps a backlog draining fairly rather than starving the
     * events that have waited longest. The bound is what keeps one tick from turning into an unbounded
     * write storm; the remainder is simply the next tick's work.
     */
    List<PendingOutboxEvent> findUnpublished(int limit);

    /**
     * Flag an event as published, so no later tick picks it up again.
     *
     * <p>Called <b>after</b> the publish succeeded, never before: a crash in that gap costs a duplicate
     * (which every consumer dedupes away by {@code eventId}), whereas marking first would cost a lost
     * event — for an external send, money parked in the clearing account that nobody ever settles.
     * Idempotent: marking an already-marked event is a no-op, not an error.
     */
    void markPublished(PendingOutboxEvent event);
}
