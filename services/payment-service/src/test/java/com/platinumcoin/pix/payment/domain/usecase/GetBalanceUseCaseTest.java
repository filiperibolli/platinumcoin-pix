package com.platinumcoin.pix.payment.domain.usecase;

import com.platinumcoin.pix.payment.domain.exception.BalanceNotFoundException;
import com.platinumcoin.pix.payment.domain.exception.LedgerUnavailableException;
import com.platinumcoin.pix.payment.domain.model.AccountBalance;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cache-aside, as a policy the use case owns (ADR-0008, ADR-0011): <b>hit ⇒ return; miss ⇒ read the
 * ledger, populate, return</b>. Two properties are what the pattern actually promises, and both are
 * asserted by counting calls rather than by inspecting logs:
 *
 * <ul>
 *   <li>a hit costs the ledger <b>nothing</b> — otherwise the cache buys no latency and no ledger
 *       relief, which is the entire justification for its existence;</li>
 *   <li>a miss populates <b>exactly once</b>, with the value it just read.</li>
 * </ul>
 *
 * <p>The {@code asOf} stamp is the use case's, from an injected {@link Clock} (CLAUDE.md: reading the
 * clock is policy, never an adapter's improvisation). It means "when the ledger was read", which is
 * what makes the ≤TTL staleness bound honest on the wire: a client can subtract it and know how old
 * the number is. Stamping it at render time instead would make every cached answer look fresh.
 */
class GetBalanceUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-08-21T12:00:00.123456789Z");
    private static final String ACCOUNT = "acc-001";

    private final FakeLedgerClient ledger = new FakeLedgerClient();
    private final FakeBalanceCache cache = new FakeBalanceCache();
    private final GetBalanceUseCase getBalance =
            new GetBalanceUseCase(cache, ledger, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void aMissReadsTheLedgerAndPopulatesTheCacheWithTheClocksInstant() {
        ledger.setBalance(ACCOUNT, 87_450L);

        AccountBalance balance = getBalance.execute(ACCOUNT);

        assertThat(balance).isEqualTo(
                new AccountBalance(ACCOUNT, 87_450L, Instant.parse("2026-08-21T12:00:00.123Z")));
        assertThat(ledger.balanceReads()).isEqualTo(1);
        assertThat(cache.puts()).isEqualTo(1);
        assertThat(cache.stored(ACCOUNT)).contains(balance);
    }

    /**
     * The whole point of the cache: the ledger is not touched. If this test ever goes green while
     * {@code balanceReads()} is 1, the platform is paying Redis's operational weight for nothing.
     */
    @Test
    void aHitIsServedFromTheCacheWithoutTouchingTheLedger() {
        AccountBalance cached =
                new AccountBalance(ACCOUNT, 12_300L, Instant.parse("2026-08-21T11:59:58Z"));
        cache.seed(cached);
        // The ledger disagrees — and must not be consulted, which is exactly how a hit can be ≤TTL
        // stale. Staleness is the deal the TTL and the invalidation bound; it is not a bug here.
        ledger.setBalance(ACCOUNT, 999_999L);

        AccountBalance balance = getBalance.execute(ACCOUNT);

        assertThat(balance).isEqualTo(cached);
        assertThat(ledger.balanceReads()).isZero();
        assertThat(cache.puts()).isZero();
    }

    /**
     * A hit is returned with the {@code asOf} it was cached with, not with "now". The age of the
     * number is a fact about the number; re-stamping it would erase the only evidence a client has
     * that it is reading something up to a TTL old.
     */
    @Test
    void aHitKeepsTheAsOfItWasCachedWith() {
        Instant cachedAt = Instant.parse("2026-08-21T11:59:58Z");
        cache.seed(new AccountBalance(ACCOUNT, 12_300L, cachedAt));

        assertThat(getBalance.execute(ACCOUNT).asOf()).isEqualTo(cachedAt);
    }

    /**
     * An account with no ledger balance is a 404, and — critically — <b>nothing is cached</b>. Caching
     * a negative answer would mean an account that opens right after a lookup stays invisible for a
     * full TTL, and there is no invalidation event for "an account that did not exist now does".
     */
    @Test
    void anUnknownAccountFailsAndCachesNothing() {
        assertThatThrownBy(() -> getBalance.execute("acc-nope"))
                .isInstanceOf(BalanceNotFoundException.class);

        assertThat(cache.puts()).isZero();
        assertThat(cache.stored("acc-nope")).isEmpty();
    }

    /**
     * A ledger failure on a miss surfaces as-is (→ 503 with Retry-After). The cache holds nothing to
     * fall back on: cache-aside's availability bonus is real but bounded by the TTL — it covers a
     * ledger blip for accounts read in the last few seconds, and nothing else.
     */
    @Test
    void aLedgerFailureOnAMissSurfacesAndCachesNothing() {
        ledger.failWith(new LedgerUnavailableException("ledger unreachable", new RuntimeException()));

        assertThatThrownBy(() -> getBalance.execute(ACCOUNT))
                .isInstanceOf(LedgerUnavailableException.class);

        assertThat(cache.puts()).isZero();
    }
}
