package com.platinumcoin.pix.common.metrics;

/**
 * The platform's <b>metric contract</b> (step 44): the names and tag values every service must use when
 * it reports a business fact, held in one place so two services cannot spell the same fact differently.
 *
 * <h2>Why a shared constant file rather than a string in each service</h2>
 * The payment funnel is the one metric family that is <i>assembled from more than one service</i>:
 * payment-service owns {@code RECEIVED → FRAUD_CHECKED → DEBITED} (and {@code SETTLED} for an internal
 * send), settlement-service owns {@code SENT_TO_SPI → SETTLED} and the {@code REVERSED} branch. A
 * dashboard panel that sums them is only correct while both sides agree on the metric name, the tag keys
 * and the exact spelling of every tag value — the classic drift where {@code stage="settled"} and
 * {@code stage="SETTLED"} become two silent half-funnels. Making the vocabulary a compile-time type
 * removes the possibility: a typo does not compile, and a new stage is added once, for everyone.
 *
 * <h2>Naming convention</h2>
 * Every platform metric is prefixed {@code pix.} — Micrometer's dot form, which the Prometheus registry
 * renders as {@code pix_payments_stage_total}. The prefix is what lets an operator (and a Grafana
 * variable) separate "signals this platform emits" from the JVM/HTTP/AWS-SDK meters the framework
 * contributes to the same registry.
 *
 * <p>Names live here; the meters themselves are registered by each service's {@code infra/} adapter,
 * because a {@code MeterRegistry} is infrastructure and the domain only names business facts (ADR-0010).
 * This class holds <b>no Micrometer type</b> on purpose — it is a vocabulary, safe to import from
 * {@code domain/} without dragging a framework in behind it.
 */
public final class PixMetrics {

    private PixMetrics() {
    }

    // ── The payment funnel ────────────────────────────────────────────────────────────────────────

    /**
     * {@code pix.payments.stage{stage,outcome}} — one increment every time a payment <i>reaches</i> a
     * stage of the send flow, tagged with what happened there. This single counter answers the whole
     * funnel: conversion between stages is the ratio of consecutive {@code outcome=ok} series, and
     * "where do payments die?" is the {@code outcome=rejected} series broken down by stage.
     */
    public static final String PAYMENTS_STAGE = "pix.payments.stage";

    /** Tag key: which stage of the send flow this increment is about ({@link Stage}). */
    public static final String STAGE_TAG = "stage";

    /** Tag key: what happened at that stage ({@link Outcome}). */
    public static final String OUTCOME_TAG = "outcome";

    /**
     * The stages of a Pix send, in funnel order. The five spellings match
     * {@code TransactionStatus} wherever a stage is also a persisted status, so a panel and a DynamoDB
     * item never disagree on what a payment's state is called — {@link #FRAUD_CHECKED} is the one
     * exception, a stage the flow passes through without persisting a distinct status (the verdict is
     * stamped on the transaction as {@code fraudDecision} instead).
     */
    public enum Stage {
        /** The request was accepted for processing: a fresh idempotency claim entered the flow. */
        RECEIVED,
        /** The in-path fraud check returned a verdict (ADR-0005) — including a fail-open skip. */
        FRAUD_CHECKED,
        /** The atomic ledger posting committed: the payer's money moved (Domain Safety Rule #4). */
        DEBITED,
        /** settlement-service durably claimed the transaction and asked the rail (external only). */
        SENT_TO_SPI,
        /** Terminal, happy: the money reached the payee — instantly (internal) or via BACEN. */
        SETTLED,
        /**
         * Terminal, compensating: a definitive non-settlement returned the parked money to the payer
         * (step 33). The funnel's {@code REVERSED} branch — a completed reversal is
         * {@code outcome=ok} here, because the compensation itself succeeded.
         */
        REVERSED
    }

    /**
     * What happened at a stage. Deliberately two values: a payment either advanced or it did not, and
     * every "did not" a user can observe is a refusal the platform issued. Failures that are <i>not</i>
     * a verdict — an unreachable ledger, an unanswered rail — are not counted as rejections: nothing was
     * decided, the request is retryable, and counting them would make the funnel report deaths that the
     * retry then resurrects.
     */
    public enum Outcome {
        /** The payment advanced past this stage. */
        OK("ok"),
        /** The payment died here: the platform refused it, definitively, at this stage. */
        REJECTED("rejected");

        private final String tagValue;

        Outcome(String tagValue) {
            this.tagValue = tagValue;
        }

        /** The lowercase form used as the {@code outcome} tag value. */
        public String tagValue() {
            return tagValue;
        }
    }

    // ── The other business counters ───────────────────────────────────────────────────────────────

    /**
     * {@code pix.fraud.decision{decision}} — the in-path fraud verdict mix as the <i>payment flow</i>
     * saw it, which is why payment-service owns it and fraud-service does not: only the caller knows
     * about {@code SKIPPED}, the fail-open that happens when the 200ms budget is blown (ADR-0005), and
     * the fail-open rate is precisely the number this metric exists to expose.
     */
    public static final String FRAUD_DECISION = "pix.fraud.decision";

    /** Tag key on {@link #FRAUD_DECISION}: the verdict, spelled as the {@code FraudDecision} enum. */
    public static final String DECISION_TAG = "decision";

    /**
     * {@code pix.settled.amount} — money that actually reached a payee, <b>in integer cents</b>
     * (Domain Safety Rule #6: cents end to end; a dashboard divides by 100 for display, the platform
     * never does). A counter, not a gauge: it only ever goes up, so a rate over it is "R$ settled per
     * second" and its increase over a window is "R$ settled in that window".
     */
    public static final String SETTLED_AMOUNT = "pix.settled.amount";

    /**
     * {@code pix.idempotency.replayed} — requests answered from a memoized response instead of moving
     * money a second time (ADR-0002). The runtime evidence for KR1.1's "0 duplicate debits": every
     * increment is a duplicate the platform absorbed, and the corresponding absence of a second
     * {@code stage=DEBITED} increment is what proves it absorbed it correctly.
     */
    public static final String IDEMPOTENCY_REPLAYED = "pix.idempotency.replayed";

    /**
     * {@code pix.reconciliation.resolved{action}} — stuck transactions the reconciliation resolver
     * forced to a terminal state (step 35), by which way they went.
     */
    public static final String RECONCILIATION_RESOLVED = "pix.reconciliation.resolved";

    /** Tag key on {@link #RECONCILIATION_RESOLVED}: {@code settled} or {@code reversed}. */
    public static final String ACTION_TAG = "action";
}
