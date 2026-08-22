package com.platinumcoin.pix.settlement.domain.port;

import com.platinumcoin.pix.common.metrics.PixMetrics.Outcome;
import com.platinumcoin.pix.common.metrics.PixMetrics.Stage;

/**
 * Outbound port for settlement-service's half of the <b>business funnel</b> (step 44):
 * {@code SENT_TO_SPI}, the terminal {@code SETTLED} of an external send, and the {@code REVERSED}
 * branch — the stages payment-service deliberately does not claim, because at hand-off time only the
 * rail knows whether the money arrived.
 *
 * <h2>Why two services write to one counter</h2>
 * {@code pix.payments.stage} is a single metric family assembled from both sides of the asynchronous
 * seam, so a Grafana panel can draw one funnel from {@code RECEIVED} to {@code SETTLED} across a process
 * boundary. That only works while both sides spell the stages identically, which is why the vocabulary is
 * a shared enum in common-lib ({@code PixMetrics}) rather than a string in each service: Prometheus would
 * happily store {@code stage="SENT_TO_SPI"} and {@code stage="sent_to_spi"} as two unrelated series, and
 * the funnel would silently split in half with nothing failing anywhere.
 *
 * <h2>The counted moment is the durable one</h2>
 * Each increment happens <b>after</b> the guarded status transition commits, never before the rail call
 * and never on an optimistic path. {@code SENT_TO_SPI} is counted once the transaction durably says
 * "BACEN was asked" — the same fact step 32's query-before-retry and step 35's reconciliation key off —
 * so the metric and the recovery logic agree on what "sent" means. An unanswered rail increments nothing:
 * the outcome is <i>unknown</i>, and a funnel that guessed would be worse than one that waits.
 */
public interface SettlementFunnelMetrics {

    /**
     * An external payment reached {@code stage} with the given {@code outcome} — recorded only for
     * durably committed transitions.
     */
    void stageReached(Stage stage, Outcome outcome);

    /**
     * Money that reached a payee via BACEN, in <b>integer cents</b> (Domain Safety Rule #6). Called once
     * per settlement, from the same place the {@code SETTLED} transition commits.
     */
    void settled(long amountCents);
}
