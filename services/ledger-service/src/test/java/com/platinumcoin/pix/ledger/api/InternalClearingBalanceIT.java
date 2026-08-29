package com.platinumcoin.pix.ledger.api;

import com.platinumcoin.pix.common.ledger.ClearingAccountResolver;
import com.platinumcoin.pix.common.security.InternalApi;
import com.platinumcoin.pix.common.testsupport.LocalStackTestBase;
import com.platinumcoin.pix.ledger.LedgerAccountFixture;
import com.platinumcoin.pix.ledger.domain.model.PostingCommand;
import com.platinumcoin.pix.ledger.domain.port.LedgerRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /internal/ledger/clearing-balance} (step 52, task 3).
 *
 * <p>Sharding costs the platform something it used to have for free: <b>"what is our clearing
 * position?" stopped being one {@code GetItem}</b>. Sixteen items hold the answer and no single one of
 * them is it, so reconciliation, the operator runbook and the load study all need somewhere to ask.
 * This endpoint is that place, and it returns the per-shard breakdown alongside the total because the
 * interesting operational question after "is it zero?" is "is it zero <i>everywhere</i>?" — a sum of
 * {@code +500} and {@code -500} is the signature of a reversal that hit the wrong shard, and a total
 * alone would hide it.
 */
@SpringBootTest
@AutoConfigureMockMvc
class InternalClearingBalanceIT extends LocalStackTestBase {

    @Autowired
    MockMvc mvc;

    @Autowired
    LedgerRepository repository;

    @Autowired
    software.amazon.awssdk.services.dynamodb.DynamoDbClient dynamo;

    @Autowired
    ClearingAccountResolver clearing;

    private static String readToken() {
        return TestTokens.forService("settlement-service", InternalApi.AUD_LEDGER,
                InternalApi.SCOPE_LEDGER_READ);
    }

    @Test
    void sumsEveryShardAndReportsThemIndividually() throws Exception {
        // Park real money in two different shards, the way two external sends would.
        // Two txIds that land on DIFFERENT shards. Picked by search rather than by hope: two random
        // ids collide on one shard 1-in-16 of the time, and a test that is flaky about the very thing
        // it asserts (that the total spans several items) is worse than no test.
        String txA = "it-clearing-a-" + System.nanoTime();
        String shardA = clearing.shardFor(txA);
        String txB;
        String shardB;
        int attempt = 0;
        do {
            txB = "it-clearing-b-" + attempt++ + "-" + System.nanoTime();
            shardB = clearing.shardFor(txB);
        } while (shardB.equals(shardA) && attempt < 200);
        assertThat(shardB).isNotEqualTo(shardA);

        // Fixture payers, never acc-001/acc-002: the step-13 ITs assert the SEEDED supply in absolute
        // terms ("alice holds exactly R$ 10,000.00"), and a test that spends it makes the suite's
        // result depend on class execution order. Same rule LedgerAccountFixture's javadoc states.
        String payerA = LedgerAccountFixture.uniqueAccountId("it-clearing-payer-a");
        String payerB = LedgerAccountFixture.uniqueAccountId("it-clearing-payer-b");
        LedgerAccountFixture.openAccount(dynamo, payerA, 10_000L);
        LedgerAccountFixture.openAccount(dynamo, payerB, 10_000L);

        long before = totalClearingCents();
        repository.post(new PostingCommand(txA, payerA, shardA, 1_500L, "PIX_OUT", "shard sum a"),
                Instant.parse("2026-08-28T10:00:00Z"));
        repository.post(new PostingCommand(txB, payerB, shardB, 2_500L, "PIX_OUT", "shard sum b"),
                Instant.parse("2026-08-28T10:00:01Z"));

        mvc.perform(get("/internal/ledger/clearing-balance")
                        .header("Authorization", "Bearer " + readToken()))
                .andExpect(status().isOk())
                // The logical position: the sum, not any one item.
                .andExpect(jsonPath("$.balanceCents", is((int) (before + 4_000L))))
                // Money at the API edge is a decimal string as well as integer cents (§ conventions).
                .andExpect(jsonPath("$.balance", is(decimal(before + 4_000L))))
                // The breakdown: 16 shards plus the un-sharded base account.
                .andExpect(jsonPath("$.shards", hasSize(clearing.clearingAccounts().size())))
                .andExpect(jsonPath("$.shards[?(@.accountId=='" + shardA + "')].balanceCents",
                        is(java.util.List.of(1_500))))
                .andExpect(jsonPath("$.shards[?(@.accountId=='" + shardB + "')].balanceCents",
                        is(java.util.List.of(2_500))))
                .andExpect(jsonPath("$.shardCount", is(clearing.shardCount())));
    }

    @Test
    void isAnInternalPortAndRefusesAnUnscopedCall() throws Exception {
        // Fail-closed by default (ADR-0017): a route absent from `jwt.internal-routes` is refused, so
        // this also pins that the new path was actually scoped rather than accidentally left open.
        mvc.perform(get("/internal/ledger/clearing-balance"))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/internal/ledger/clearing-balance")
                        .header("Authorization", "Bearer " + TestTokens.forService(
                                "settlement-service", InternalApi.AUD_LEDGER, InternalApi.SCOPE_LEDGER_POST)))
                .andExpect(status().isForbidden());
    }

    @Test
    void everyShardIsReadableSoTheAnswerIsNeverPartial() throws Exception {
        // A missing BALANCE item would make the sum silently short. Asserting each one is present is
        // how the seed script's shard creation (task 4) is pinned from the application's side.
        mvc.perform(get("/internal/ledger/clearing-balance")
                        .header("Authorization", "Bearer " + readToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shards[*].accountId",
                        is(clearing.clearingAccounts())))
                .andExpect(jsonPath("$.missingAccounts", hasSize(0)))
                .andExpect(jsonPath("$.shards[0].balanceCents", greaterThanOrEqualTo(Integer.MIN_VALUE)));
    }

    private long totalClearingCents() {
        return clearing.clearingAccounts().stream()
                .mapToLong(id -> repository.getBalance(id).map(b -> b.balanceCents()).orElse(0L))
                .sum();
    }

    private static String decimal(long cents) {
        return java.math.BigDecimal.valueOf(cents).movePointLeft(2).toPlainString();
    }
}
