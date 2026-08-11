package com.platinumcoin.pix.payment.domain.port;

import com.platinumcoin.pix.payment.domain.model.FraudDecision;
import java.time.Instant;

/**
 * Outbound port for scoring a send against fraud rules — fraud-service owns the velocity counters and
 * rule engine (ADR-0006), so payment-service never scores itself; it asks. The domain declares the
 * shape; {@code infra/} implements it against {@code POST /internal/fraud/score} (so no HTTP type
 * reaches the use case, ADR-0010).
 *
 * <p><b>The fail-open lives behind this port, not in front of it (ADR-0005).</b> The port always returns
 * a {@link FraudDecision} — it never throws for a slow or broken fraud-service. The 200ms client budget
 * (connect 50ms / read 150ms) and the translation of a timeout or error into {@link FraudDecision#SKIPPED}
 * are the adapter's job, because "the call took too long / the host is down" is an infrastructure fact
 * only the adapter can observe. The use case then applies one business rule to the four-valued result:
 * {@code DENY} blocks; {@code APPROVE}/{@code REVIEW}/{@code SKIPPED} all proceed (the last two flagged).
 * Keeping the fail-<i>open</i> at the boundary is what lets the use case stay a straight-line policy with
 * no knowledge of HTTP, timeouts or retries.
 *
 * <p>Money is integer cents end to end — the port speaks {@code long} cents, never a decimal string.
 */
public interface FraudScorer {

    /**
     * Score a candidate send and return the verdict to act on. Never throws for a fraud-service failure:
     * a timeout past the budget or any transport/5xx error is returned as {@link FraudDecision#SKIPPED}
     * (fail-open, ADR-0005), so the caller always gets a decision it can proceed or block on.
     *
     * @param accountId   the payer, from the JWT {@code accountId} claim (Domain Safety Rule #1) — never
     *                    from the client payload
     * @param pixKey      the destination key as the client sent it (the payee signal)
     * @param amountCents the send amount in integer cents
     * @param timestamp   the send instant, read from the injected clock by the use case — the odd-hours
     *                    signal is computed from it
     * @return {@code APPROVE} / {@code REVIEW} / {@code DENY} from fraud-service, or {@code SKIPPED} when
     *         the check could not complete inside the budget
     */
    FraudDecision score(String accountId, String pixKey, long amountCents, Instant timestamp);
}
