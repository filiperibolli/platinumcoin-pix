package com.platinumcoin.pix.common.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The lane registry (step 71, ADR-0019) — a pure decision table, so it gets a pure test.
 */
class OutboxLaneTest {

    /**
     * The whole ranking, one row per event type. The ordering claim these rows encode is the one
     * ADR-0019 makes: the event a <b>money flow</b> is blocked on outranks the event a <b>person</b> is
     * waiting on, which outranks the event only the <b>trail</b> reads.
     */
    @ParameterizedTest(name = "{0} goes out on the {1} lane")
    @CsvSource({
            "PixDebited,        SETTLEMENT",
            "PixSettled,        NOTIFICATION",
            "PixReceived,       NOTIFICATION",
            "PixReversed,       NOTIFICATION",
            "FraudCheckSkipped, AUDIT",
    })
    void everyEventTypeMapsToItsLane(String eventType, OutboxLane expected) {
        assertThat(OutboxLane.forEventType(eventType)).isEqualTo(expected);
    }

    /**
     * <b>Unmapped is a failure, not an {@code AUDIT} default.</b> A default would mean a new
     * money-critical event type lands silently on the slowest drain — which is precisely the shape of
     * the incident in {@code docs/load/RESULTS.md} Context 2, just with a new cause.
     */
    @Test
    void anUnregisteredEventTypeIsRefusedRatherThanDefaultedToAudit() {
        assertThatThrownBy(() -> OutboxLane.forEventType("PixSomethingNew"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No outbox lane is registered")
                .hasMessageContaining("PixSomethingNew");
    }

    /**
     * Each lane is its own partition on the shared sparse index — that single fact is what makes one
     * lane's backlog invisible to another lane's poll, with no second table and no schema change.
     */
    @Test
    void eachLaneIsItsOwnPartitionOnTheSparseIndex() {
        assertThat(OutboxLane.SETTLEMENT.gsi3pk()).isEqualTo("OUTBOX#UNPUBLISHED#SETTLEMENT");
        assertThat(OutboxLane.NOTIFICATION.gsi3pk()).isEqualTo("OUTBOX#UNPUBLISHED#NOTIFICATION");
        assertThat(OutboxLane.AUDIT.gsi3pk()).isEqualTo("OUTBOX#UNPUBLISHED#AUDIT");

        assertThat(java.util.Arrays.stream(OutboxLane.values()).map(OutboxLane::gsi3pk).distinct().count())
                .as("two lanes sharing a partition key would silently be one lane again")
                .isEqualTo(OutboxLane.values().length);
    }
}
