package com.platinumcoin.pix.payment.api;

import com.platinumcoin.pix.common.testsupport.LocalStackTestBase;
import com.platinumcoin.pix.common.testsupport.RedisTestBase;
import com.platinumcoin.pix.payment.support.PaymentTestSupport;
import com.platinumcoin.pix.payment.support.StubLedgerClient;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /v1/accounts/me/balance} end to end through the wired service (step 40, ADR-0008): the
 * HTTP contract, the cache-aside behaviour over a real Redis, and the metrics the hit rate is read
 * from. The ledger is the in-memory {@link StubLedgerClient} — this test is about the cache and the
 * edge, not about DynamoDB arithmetic, which ledger-service's own suite owns.
 *
 * <p>The eviction is performed here <b>by hand</b>, as ledger-service's post-commit
 * {@code RedisBalanceCacheInvalidator} does over the shared {@code balance:<accountId>} key. That the
 * ledger really issues that DEL after a committed posting is proven on the other side of the seam, in
 * ledger-service's {@code BalanceCacheInvalidationIT}; here it stands for "something invalidated the
 * key", which is what this service must react to.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PaymentTestSupport.class)
class BalanceCacheIT extends LocalStackTestBase {

    private static final String ACCOUNT = "acc-001";
    private static final String KEY = "balance:" + ACCOUNT;

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        RedisTestBase.registerRedisProperties(registry);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    StubLedgerClient ledger;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    MeterRegistry meters;

    @Autowired
    LettuceConnectionFactory redisConnectionFactory;

    private String token;

    @BeforeEach
    void resetCacheAndLedger() {
        token = TestTokens.forUser("u-alice", ACCOUNT);
        redis.delete(KEY);
        ledger.setBalance(ACCOUNT, 87_450L);
    }

    private double counter(String name) {
        var found = meters.find(name).counter();
        return found == null ? 0d : found.count();
    }

    /**
     * The money edge: integer cents inside, a decimal string on the wire, plus the currency and the
     * {@code asOf} that says how old the number is. And the side effect that makes the next read fast —
     * the value is now in Redis, with a TTL.
     */
    @Test
    void aMissAnswersFromTheLedgerAndPopulatesRedisWithATtl() throws Exception {
        mvc.perform(get("/v1/accounts/me/balance").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId", is(ACCOUNT)))
                .andExpect(jsonPath("$.balance", is("874.50")))
                .andExpect(jsonPath("$.currency", is("BRL")))
                .andExpect(jsonPath("$.asOf", notNullValue()));

        assertThat(redis.opsForValue().get(KEY)).contains("\"balanceCents\":87450");
        // The 5s of application.yml, asserted where the configured value actually applies.
        assertThat(redis.getExpire(KEY, TimeUnit.SECONDS)).isNotNull().isPositive()
                .isLessThanOrEqualTo(5L);
    }

    /**
     * <b>The hit is a real hit.</b> The ledger's answer is changed underneath and the endpoint keeps
     * returning the cached one — proof that the second call did not go to the ledger, and, in the same
     * breath, an honest picture of the staleness the design accepts: bounded by the TTL and by the
     * writer's eviction, and irrelevant to money because no debit consults this number.
     */
    @Test
    void aSecondReadIsServedFromTheCacheEvenAfterTheLedgerMoved() throws Exception {
        mvc.perform(get("/v1/accounts/me/balance").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.balance", is("874.50")));

        ledger.setBalance(ACCOUNT, 10_00L);

        mvc.perform(get("/v1/accounts/me/balance").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance", is("874.50")));
    }

    /**
     * …and the eviction closes that window immediately: once the key is gone — as it is after every
     * posting the ledger commits — the very next read shows the new number.
     */
    @Test
    void anEvictionMakesTheNextReadReflectTheNewBalance() throws Exception {
        mvc.perform(get("/v1/accounts/me/balance").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.balance", is("874.50")));

        ledger.setBalance(ACCOUNT, 10_00L);
        redis.delete(KEY);

        mvc.perform(get("/v1/accounts/me/balance").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance", is("10.00")));
    }

    /** The KPI behind the 300ms budget: one miss then one hit, counted (step 44 graphs these). */
    @Test
    void hitAndMissAreCounted() throws Exception {
        double missesBefore = counter("cache.miss");
        double hitsBefore = counter("cache.hit");

        mvc.perform(get("/v1/accounts/me/balance").header("Authorization", "Bearer " + token));
        mvc.perform(get("/v1/accounts/me/balance").header("Authorization", "Bearer " + token));

        assertThat(counter("cache.miss")).isEqualTo(missesBefore + 1);
        assertThat(counter("cache.hit")).isEqualTo(hitsBefore + 1);
    }

    /**
     * <b>The cache may never hang a request.</b> Lettuce defaults to a <b>60 second</b> command
     * timeout, and a Redis that is <i>stopped</i> (packets dropped, not refused) hangs for all of it —
     * which in the step-40 drill turned a balance read into a ~114s request. `application.yml` bounds
     * it; this asserts the wired client actually carries that bound, so deleting the property is a
     * failing build rather than an incident.
     */
    @Test
    void theWiredRedisClientHasABoundedCommandTimeout() {
        assertThat(redisConnectionFactory.getTimeout())
                .as("spring.data.redis.timeout must stay small — a hung cache must cost ms, not minutes")
                .isPositive()
                .isLessThanOrEqualTo(1_000L);
    }

    /**
     * An account with no ledger balance is {@code 404}, not a confident {@code R$ 0,00} — and nothing
     * is cached, so the account becomes visible the instant it exists (there is no eviction event for
     * "an account that did not exist now does").
     */
    @Test
    void anAccountWithNoLedgerBalanceIs404ProblemJsonAndIsNotCached() throws Exception {
        String orphan = "acc-orphan";
        ledger.markUnknown(orphan);
        redis.delete("balance:" + orphan);

        mvc.perform(get("/v1/accounts/me/balance")
                        .header("Authorization", "Bearer " + TestTokens.forUser("u-ghost", orphan)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code", is("BALANCE_NOT_FOUND")))
                .andExpect(jsonPath("$.correlationId").exists());

        assertThat(redis.hasKey("balance:" + orphan)).isFalse();
    }

    /**
     * The balance is {@code /me} and nothing else: no token, no read. There is no path or query
     * parameter that could name another account, so this is the only way to ask the question wrong.
     */
    @Test
    void withoutTokenFailsClosedWith401() throws Exception {
        mvc.perform(get("/v1/accounts/me/balance"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", is("UNAUTHORIZED")));
    }
}
