package com.platinumcoin.pix.common.ledger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Step 52, task 1 and its first test. The resolver is a pure function and this is the whole of its
 * contract, because everything downstream of it — which shard a debit credits, which shard its
 * reversal has to compensate — is decided here and nowhere else.
 */
class ClearingAccountResolverTest {

    private static final String BASE = "SPI_CLEARING";

    @Test
    @DisplayName("the same txId always resolves to the same shard, in this JVM and any other")
    void mappingIsStable() {
        var resolver = new ClearingAccountResolver(BASE, 16);

        // Stability WITHIN a run is the weak half of the claim: a reversal reads the shard off the
        // transaction (step 33) rather than re-deriving it, precisely so a drift here cannot move
        // money. The strong half is the pinned value below.
        String first = resolver.shardFor("tx-9f1c");
        for (int i = 0; i < 100; i++) {
            assertThat(resolver.shardFor("tx-9f1c")).isEqualTo(first);
        }

        // Pinned literals: CRC32 is specified by the JDK, so these are the answers on every machine
        // and every release. If a change of hash function ever makes this test fail, that is the point
        // — it means already-persisted transactions would now resolve elsewhere.
        assertThat(resolver.shardFor("tx-9f1c")).isEqualTo("SPI_CLEARING#06");
        assertThat(resolver.shardFor("in-E1234567820260828120000abcdef")).isEqualTo("SPI_CLEARING#07");
    }

    @Test
    @DisplayName("N=1 reproduces the un-sharded account exactly — the baseline run's before picture")
    void singleShardIsTheOldBehaviour() {
        var resolver = new ClearingAccountResolver(BASE, 1);

        assertThat(resolver.shardFor("tx-" + UUID.randomUUID())).isEqualTo(BASE);
        assertThat(resolver.allShards()).containsExactly(BASE);
    }

    @Test
    @DisplayName("10k txIds spread evenly enough that no shard is a hot partition of its own")
    void distributionIsUniformish() {
        var resolver = new ClearingAccountResolver(BASE, 16);
        Map<String, Integer> hits = new HashMap<>();

        for (int i = 0; i < 10_000; i++) {
            hits.merge(resolver.shardFor("tx-" + UUID.randomUUID()), 1, Integer::sum);
        }

        // Every shard used, and none carrying more than ~1.25x its fair share. The bound is loose on
        // purpose: this asserts "no shard is hot", not that CRC32 is a CSPRNG.
        assertThat(hits).hasSize(16);
        assertThat(hits.values()).allSatisfy(count ->
                assertThat(count).isBetween(500, 780));
    }

    @Test
    @DisplayName("allShards() is the full, ordered set the clearing balance is summed over")
    void allShardsEnumeratesEveryAccount() {
        assertThat(new ClearingAccountResolver(BASE, 4).allShards())
                .containsExactly("SPI_CLEARING#00", "SPI_CLEARING#01",
                        "SPI_CLEARING#02", "SPI_CLEARING#03");

        // Every id the resolver can ever produce is one of the ids it enumerates. That equality is
        // what makes "the logical balance is the sum of the shards" true rather than approximately
        // true — a debit landing outside the summed set would be money the platform cannot see.
        var sixteen = new ClearingAccountResolver(BASE, 16);
        List<String> enumerated = sixteen.allShards();
        for (int i = 0; i < 2_000; i++) {
            assertThat(enumerated).contains(sixteen.shardFor("tx-" + UUID.randomUUID()));
        }
    }

    @Test
    @DisplayName("the set to SUM includes the un-sharded base account; the set to ASSIGN does not")
    void clearingAccountsIsWiderThanTheShardSet() {
        var sharded = new ClearingAccountResolver(BASE, 4);

        // Assignment never produces the bare id...
        assertThat(sharded.allShards()).doesNotContain(BASE);
        // ...but the position has to be summed over it anyway, because money credited before the shard
        // count was raised is sitting there and reverses out of there.
        assertThat(sharded.clearingAccounts())
                .containsExactly(BASE, BASE + "#00", BASE + "#01", BASE + "#02", BASE + "#03");

        // With sharding off the two sets are the same thing — there is no other account in play.
        assertThat(new ClearingAccountResolver(BASE, 1).clearingAccounts()).containsExactly(BASE);
    }

    @Test
    @DisplayName("a shard count outside [1,100] is refused at construction, not at the first payment")
    void refusesAnUnusableShardCount() {
        assertThatThrownBy(() -> new ClearingAccountResolver(BASE, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("clearing shards");
        assertThatThrownBy(() -> new ClearingAccountResolver(BASE, 101))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("clearing shards");
        assertThatThrownBy(() -> new ClearingAccountResolver("  ", 16))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
