package com.platinumcoin.pix.common.event;

import java.util.Map;

/**
 * Which drain an outbox event goes out on (step 71, ADR-0019).
 *
 * <h2>Why lanes exist at all</h2>
 * Until step 71 the outbox was one FIFO: every event, whatever it was, waited behind every event
 * written before it. {@code docs/load/RESULTS.md} Context 2 records what that costs — a correct
 * external payment was {@code REVERSED} by reconciliation because its {@code PixDebited} queued behind
 * <b>55,538 internal {@code PixSettled} events that no queue was even subscribed to</b>, and crossed the
 * 120s stuck threshold while it waited. Nothing was lost and nothing was incorrect; the *latency* of an
 * unrelated event type undid a payment. A lane is the fix: the drain is partitioned, so a queue nobody
 * is reading cannot delay a queue that moves money.
 *
 * <h2>Named for who waits, not for who emits</h2>
 * The three names answer "what is blocked while this event sits here?", which is the only question that
 * can rank them:
 * <ul>
 *   <li>{@link #SETTLEMENT} — <b>a money flow is blocked.</b> {@code PixDebited} is the trigger
 *       settlement-service consumes; until it is published, the payer's money sits in the clearing
 *       account with nothing on its way to release it, and the stuck-transaction scanner is counting.</li>
 *   <li>{@link #NOTIFICATION} — <b>a person is waiting to see it.</b> The terminal events
 *       ({@code PixSettled}, {@code PixReceived}, {@code PixReversed}) drive the SSE stream and the
 *       statement. Late is a bad experience; late is not a wrong balance.</li>
 *   <li>{@link #AUDIT} — <b>only the trail consumes it.</b> {@code FraudCheckSkipped} feeds async
 *       re-scoring and the immutable log. Minutes here cost nothing anyone can observe.</li>
 * </ul>
 *
 * <p><b>An event with several subscribers takes the most urgent lane it belongs to.</b> That is a
 * deliberate asymmetry: a lane is a property of the <i>drain</i>, not of the topic. SNS fan-out is
 * untouched — a {@code PixSettled} still reaches the notification queue <i>and</i> the audit consumer;
 * the lane only decides which publisher takes it off the index and how fast.
 *
 * <h2>Why an unknown event type throws</h2>
 * The tempting default is "anything unmapped is {@code AUDIT}" — and it is exactly wrong, because the
 * failure it produces is the one this class exists to prevent: a new money-critical event type silently
 * lands in the slowest lane and nobody finds out until a payment reverses. Mapping is therefore
 * mandatory and unmapped is a hard failure. It surfaces at build time rather than in production because
 * this method runs while the event is being <i>constructed</i>, before the {@code TransactWriteItems} —
 * so any test that emits an unmapped type fails, and {@code OutboxWriteIT#everyEventTypeIsAssignedItsLane}
 * pins the table itself.
 */
public enum OutboxLane {

    SETTLEMENT,
    NOTIFICATION,
    AUDIT;

    /**
     * The registry. Adding an event type to the platform means adding it here, in the same change —
     * there is no fallback that would let you forget.
     */
    private static final Map<String, OutboxLane> BY_EVENT_TYPE = Map.of(
            "PixDebited", SETTLEMENT,
            "PixSettled", NOTIFICATION,
            "PixReceived", NOTIFICATION,
            "PixReversed", NOTIFICATION,
            "FraudCheckSkipped", AUDIT,
            // A person IS waiting on this one: the customer who asked for the export is polling for it
            // (step 53). It moves no money, so it is not SETTLEMENT; but it is the trigger of work
            // somebody is watching a spinner for, which is exactly what separates NOTIFICATION from
            // AUDIT — "late is a bad experience, late is not a wrong balance".
            "StatementExportRequested", NOTIFICATION);

    /**
     * The partition key this lane's events carry on the sparse publisher index.
     *
     * <p>Before step 71 this was one constant, {@code OUTBOX#UNPUBLISHED}, for every event in the
     * platform — which is the same statement as "one FIFO". Scoping it by lane turns the single index
     * into three independent ordered queues on the <b>same</b> GSI, with no schema change at all
     * ({@code gsi3pk} was always a string hash key). Within a lane the sort key is unchanged, so
     * ADR-0004's oldest-first drain is preserved exactly where it ever meant anything.
     */
    public String gsi3pk() {
        return "OUTBOX#UNPUBLISHED#" + name();
    }

    /**
     * The lane an event type belongs to.
     *
     * @throws IllegalArgumentException if the type has no lane — see the class javadoc for why this is
     *                                  a failure rather than a default
     */
    public static OutboxLane forEventType(String eventType) {
        OutboxLane lane = BY_EVENT_TYPE.get(eventType);
        if (lane == null) {
            throw new IllegalArgumentException(
                    "No outbox lane is registered for event type '" + eventType + "'. Every event type "
                            + "must declare which drain it goes out on (ADR-0019): add it to "
                            + "OutboxLane.BY_EVENT_TYPE. Defaulting to AUDIT is deliberately not an "
                            + "option — it would put a money-critical event on the slowest lane "
                            + "silently.");
        }
        return lane;
    }

    /** Parse a stored {@code lane} attribute back into the enum, for the publisher reading an item. */
    public static OutboxLane of(String name) {
        return valueOf(name);
    }

    /** The event types this platform emits, for the test that pins the registry. */
    public static Map<String, OutboxLane> registry() {
        return BY_EVENT_TYPE;
    }
}
