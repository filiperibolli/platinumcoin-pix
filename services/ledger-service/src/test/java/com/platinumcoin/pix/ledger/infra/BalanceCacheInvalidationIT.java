package com.platinumcoin.pix.ledger.infra;

import com.platinumcoin.pix.common.testsupport.LocalStackTestBase;
import com.platinumcoin.pix.common.testsupport.RedisTestBase;
import com.platinumcoin.pix.ledger.LedgerAccountFixture;
import com.platinumcoin.pix.ledger.domain.exception.InsufficientFundsException;
import com.platinumcoin.pix.ledger.domain.model.PostingCommand;
import com.platinumcoin.pix.ledger.domain.port.LedgerRepository;
import com.platinumcoin.pix.ledger.domain.usecase.PostDoubleEntryUseCase;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Invalidation-on-write over a <b>real Redis and a real DynamoDB</b> (step 40, ADR-0008): the two
 * halves of the write path that the use-case unit test can only see through a fake.
 *
 * <p>Two containers at once is why this class extends {@link LocalStackTestBase} and calls
 * {@link RedisTestBase#registerRedisProperties} by hand — Java has single inheritance, and a posting
 * commits in DynamoDB before it evicts in Redis.
 *
 * <p><b>The last test is the money one</b>, and it is the reason the whole cache is allowed to exist:
 * a cache stuffed with a fictitious, far-larger balance does <i>not</i> let a debit through. The guard
 * is the {@code balanceCents >= :amount} condition inside the {@code TransactWriteItems} (step 14,
 * Domain Safety Rule #3) and it reads DynamoDB, so Redis has no vote — however stale, however wrong,
 * however absent.
 */
@SpringBootTest
class BalanceCacheInvalidationIT extends LocalStackTestBase {

    private static final long OPENING_BALANCE = 500_000L;

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        RedisTestBase.registerRedisProperties(registry);
    }

    @Autowired
    PostDoubleEntryUseCase postDoubleEntry;

    @Autowired
    DynamoDbClient dynamo;

    @Autowired
    LedgerRepository repository;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    LettuceConnectionFactory redisConnectionFactory;

    private String payer;
    private String payee;

    @BeforeEach
    void openAccounts() {
        payer = LedgerAccountFixture.uniqueAccountId("cache-payer");
        payee = LedgerAccountFixture.uniqueAccountId("cache-payee");
        LedgerAccountFixture.openAccount(dynamo, payer, OPENING_BALANCE);
        LedgerAccountFixture.openAccount(dynamo, payee, 0L);
    }

    /** Whatever payment-service would have cached — the shape is irrelevant here, the key is not. */
    private void cacheBalance(String accountId, long balanceCents) {
        redis.opsForValue().set(
                "balance:" + accountId,
                "{\"balanceCents\":" + balanceCents + ",\"asOf\":\"2026-08-21T10:00:00Z\"}");
    }

    private boolean isCached(String accountId) {
        return Boolean.TRUE.equals(redis.hasKey("balance:" + accountId));
    }

    /** The truth, read from the ledger itself — the only place a balance is ever authoritative. */
    private long balanceOf(String accountId) {
        return repository.getBalance(accountId).orElseThrow().balanceCents();
    }

    /**
     * <b>A hung cache must not hold the money path open.</b> The eviction runs after the posting has
     * committed, inside the HTTP request, so Lettuce's default 60s command timeout would push the
     * response past payment-service's 3s read timeout — and the caller would be told
     * {@code 503 LEDGER_UNAVAILABLE} about a debit that in fact landed. That is exactly what the
     * step-40 drill produced. The bound lives in {@code application.yml}; this makes removing it a
     * failing build.
     */
    @Test
    void theWiredRedisClientHasABoundedCommandTimeout() {
        assertThat(redisConnectionFactory.getTimeout())
                .as("a posting must never wait on Redis longer than its own caller will wait on it")
                .isPositive()
                .isLessThanOrEqualTo(1_000L);
    }

    @Test
    void aCommittedPostingEvictsBothLegsFromRedis() {
        cacheBalance(payer, OPENING_BALANCE);
        cacheBalance(payee, 0L);

        postDoubleEntry.execute(
                new PostingCommand("tx-" + payer, payer, payee, 12_550L, "PIX_INTERNAL", "rent"));

        // Both keys go away, so the next balance read is a miss that goes to the ledger and sees the
        // number the money actually is — the whole of "invalidation on write". `await` rather than a
        // bare assertion because the DEL is deliberately OFF the posting's thread: the money path must
        // not wait on the cache (see RedisBalanceCacheInvalidator). The wait is milliseconds; what is
        // being asserted is that it happens, not when.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(isCached(payer)).isFalse();
            assertThat(isCached(payee)).isFalse();
        });
    }

    /**
     * Evicting a key nobody cached is the ordinary case (a balance not read in the last 5s), not an
     * error — the ledger must not care whether anything was there.
     */
    @Test
    void evictingUncachedAccountsIsANoOpAndTheMoneyStillMoves() {
        postDoubleEntry.execute(
                new PostingCommand("tx-cold-" + payer, payer, payee, 1_000L, "PIX_INTERNAL", "cold"));

        assertThat(balanceOf(payee)).isEqualTo(1_000L);
        assertThat(isCached(payer)).isFalse();
    }

    /**
     * <b>The correctness rule of ADR-0008, as a test.</b> Redis claims the payer has ten times the
     * money; the ledger says otherwise, and the ledger is the one holding the condition expression.
     * The debit is refused and not a cent moves.
     */
    @Test
    void aStaleCacheDoesNotAuthorizeAnOverdraft() {
        cacheBalance(payer, OPENING_BALANCE * 10);

        assertThatThrownBy(() -> postDoubleEntry.execute(new PostingCommand(
                "tx-overdraft-" + payer, payer, payee, OPENING_BALANCE + 1, "PIX_INTERNAL", "too much")))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(balanceOf(payer)).isEqualTo(OPENING_BALANCE);
        assertThat(balanceOf(payee)).isZero();
        // And the lie is still in the cache: a refused posting evicts nothing, because nothing became
        // stale. It expires on its own TTL — which is a display problem, never a money one.
        assertThat(isCached(payer)).isTrue();
    }
}
