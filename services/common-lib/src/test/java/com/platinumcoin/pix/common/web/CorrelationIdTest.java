package com.platinumcoin.pix.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * The asynchronous half of ADR-0012's promise (step 29): a worker that picks up an event created by
 * some earlier request restores that request's ids, so the shared log <b>pattern</b> — not a
 * hand-written field in each statement — carries {@code [cid=… tx=…]} on every line it emits.
 */
class CorrelationIdTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void restoringPutsBothIdsWhereTheLogPatternReadsThem() {
        CorrelationId.restore("corr-1", "tx-1");

        assertThat(MDC.get(CorrelationId.MDC_KEY)).isEqualTo("corr-1");
        assertThat(MDC.get(CorrelationId.TX_ID_MDC_KEY)).isEqualTo("tx-1");
        assertThat(CorrelationId.current()).isEqualTo("corr-1");
    }

    /**
     * An event minted outside a request carries no correlation id. Storing a blank one would print an
     * empty prefix that reads like a real id; leaving it unset lets the pattern show its placeholder.
     */
    @Test
    void aMissingOrBlankIdIsNotStoredAtAll() {
        CorrelationId.restore(null, "  ");

        assertThat(MDC.get(CorrelationId.MDC_KEY)).isNull();
        assertThat(MDC.get(CorrelationId.TX_ID_MDC_KEY)).isNull();
    }

    /**
     * Worker threads are pooled: an id left behind would label the <i>next</i>, unrelated event, which
     * is worse than no id at all — it would make one {@code grep} return another payment's lines.
     */
    @Test
    void clearingLeavesNothingBehindForTheNextPieceOfWork() {
        CorrelationId.restore("corr-1", "tx-1");

        CorrelationId.clear();

        assertThat(MDC.get(CorrelationId.MDC_KEY)).isNull();
        assertThat(MDC.get(CorrelationId.TX_ID_MDC_KEY)).isNull();
    }
}
