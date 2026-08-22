package com.platinumcoin.pix.payment.domain.port;

import com.platinumcoin.pix.common.metrics.PixMetrics.Outcome;
import com.platinumcoin.pix.common.metrics.PixMetrics.Stage;
import com.platinumcoin.pix.payment.domain.model.FraudDecision;

/**
 * Outbound port for the <b>business funnel</b> (step 44): the send flow announces which stage a payment
 * reached and what happened there, and an {@code infra/} adapter turns that into tagged Micrometer
 * counters. The half of the funnel payment-service owns — {@code RECEIVED → FRAUD_CHECKED → DEBITED},
 * plus {@code SETTLED} for an internal send, which settles the instant its ledger posting commits.
 *
 * <h2>Why the funnel is instrumented in the use case, not in the controller or an adapter</h2>
 * The funnel answers a <i>product</i> question — "where do payments die, and why?" — and the answer is
 * only where the decisions are made. A controller sees an HTTP status: it cannot tell a limit refusal
 * from a fraud denial without re-deriving the reason from an exception type, which is exactly the
 * duplicated policy ADR-0011 keeps out of {@code api/}. An adapter sees one call, never the flow. The use
 * case is the only place that knows a payment passed fraud and then died at the ledger.
 *
 * <h2>Why this is a port and not a {@code MeterRegistry} injected into the domain</h2>
 * Same reason as {@code ReconciliationMetrics} in settlement-service: a meter is infrastructure
 * (ADR-0010). The domain names the business fact — "a payment was rejected at the fraud stage" — and the
 * adapter decides it is a counter with two tags. It also keeps the funnel testable as <i>behaviour</i>:
 * a fake records the calls, so a unit test can assert "exactly one DEBITED increment" without a registry.
 *
 * <h2>What is deliberately NOT counted</h2>
 * Failures that decided nothing: an unreachable ledger, an unreachable fraud-service (which fails
 * <i>open</i> and continues as {@code SKIPPED}), a {@code 409} while a concurrent request with the same
 * key is in flight. None of them is a payment's death — the client retries and the payment continues —
 * and counting them would make the funnel report drop-offs that the retry silently resurrects, which is
 * the fastest way to make an operator stop trusting the graph.
 */
public interface PaymentFunnelMetrics {

    /**
     * A payment reached {@code stage} and either advanced ({@link Outcome#OK}) or was definitively
     * refused there ({@link Outcome#REJECTED}).
     */
    void stageReached(Stage stage, Outcome outcome);

    /**
     * The in-path fraud verdict, as the payment flow saw it — including {@link FraudDecision#SKIPPED},
     * the fail-open that only this side of the call can observe (ADR-0005). The {@code SKIPPED} share of
     * this counter <i>is</i> the fail-open rate the KPI table asks for.
     */
    void fraudDecision(FraudDecision decision);

    /**
     * Money that reached a payee, in <b>integer cents</b> (Domain Safety Rule #6). Called only on a
     * genuine settlement — for this service, an internal send, whose ledger posting is the settlement.
     */
    void settled(long amountCents);

    /**
     * A request was answered from a memoized response instead of moving money again (ADR-0002). Every
     * increment is a duplicate the platform absorbed — the runtime evidence for KR1.1.
     */
    void idempotentReplay();
}
