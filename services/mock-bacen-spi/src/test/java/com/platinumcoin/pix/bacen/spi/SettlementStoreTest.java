package com.platinumcoin.pix.bacen.spi;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The idempotency guarantee of the SPI's memory, proven at the store rather than only through HTTP: one
 * terminal outcome per {@code endToEndId}, forever, even under a concurrency storm.
 */
class SettlementStoreTest {

    private static final Instant FIRST_TIME = Instant.parse("2026-08-12T10:00:00Z");
    private static final Instant LATER = Instant.parse("2026-08-12T10:00:05Z");

    private final SettlementStore store = new SettlementStore();

    @Test
    void theFirstOutcomeForAnEndToEndIdWinsForever() {
        var first = store.register("E1", () -> Settlement.settled("E1", 20_000L, "bob@otherbank.com",
                "99999999", FIRST_TIME));

        // A retry — the caller timed out and re-sent. It must NOT settle again, and must be told the same
        // story: same amount, same timestamp, indistinguishable from the original response.
        var replay = store.register("E1", () -> Settlement.settled("E1", 20_000L, "bob@otherbank.com",
                "99999999", LATER));

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.settlement()).isEqualTo(first.settlement());
        assertThat(replay.settlement().recordedAt()).isEqualTo(FIRST_TIME);
    }

    @Test
    void aRejectionIsTerminalToo() {
        // FAILED is as terminal as SETTLED: a permanently refused transfer must not become settleable on a
        // later attempt, or step 33's reversal could race a late success.
        store.register("E2", () -> Settlement.rejected("E2", 5_000L, "ghost@nowhere.com",
                "CREDITOR_KEY_NOT_IN_DICT", FIRST_TIME));

        var replay = store.register("E2", () -> Settlement.settled("E2", 5_000L, "ghost@nowhere.com",
                "99999999", LATER));

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.settlement().status()).isEqualTo(SettlementStatus.FAILED);
        assertThat(replay.settlement().rejectionReason()).isEqualTo("CREDITOR_KEY_NOT_IN_DICT");
    }

    @Test
    void aStormOfConcurrentRetriesForOneIdSettlesExactlyOnce() throws Exception {
        // A redelivered SQS message racing its original is exactly this. The outcome supplier counts its
        // own invocations, so "settled once" is measured at the decision, not inferred from the reads.
        int threads = 32;
        var invocations = new AtomicInteger();
        var startLine = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<SettlementStore.Registration>> results = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    startLine.await(5, TimeUnit.SECONDS);
                    return store.register("E-STORM", () -> {
                        invocations.incrementAndGet();
                        return Settlement.settled("E-STORM", 12_345L, "bob@otherbank.com", "99999999",
                                FIRST_TIME);
                    });
                }));
            }
            startLine.countDown();

            long replayed = 0;
            for (Future<SettlementStore.Registration> result : results) {
                if (result.get(10, TimeUnit.SECONDS).replayed()) {
                    replayed++;
                }
            }
            assertThat(invocations.get()).isEqualTo(1);
            assertThat(replayed).isEqualTo(threads - 1L);
        }

        assertThat(store.find("E-STORM")).get().extracting(Settlement::amountCents).isEqualTo(12_345L);
    }

    @Test
    void anUnseenIdIsSimplyAbsent() {
        // Absence is what the controller reports as UNKNOWN — the store never invents a placeholder.
        assertThat(store.find("E-never-sent")).isEmpty();
    }
}
