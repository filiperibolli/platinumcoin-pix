package com.platinumcoin.pix.ledger.infra;

import com.platinumcoin.pix.common.ledger.ClearingAccountResolver;
import com.platinumcoin.pix.common.testsupport.LocalStackTestBase;
import com.platinumcoin.pix.ledger.LedgerAccountFixture;
import com.platinumcoin.pix.ledger.domain.exception.InsufficientFundsException;
import com.platinumcoin.pix.ledger.domain.exception.LedgerBusyException;
import com.platinumcoin.pix.ledger.domain.model.PostingCommand;
import com.platinumcoin.pix.ledger.domain.port.LedgerRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>Step 52, task 5 — the invariant that sharding could break and nothing else would notice.</b>
 *
 * <p>The step-15 suite already proves conservation across a storm, but it proves it <i>globally</i>:
 * Σ over every account is unchanged. Sharding introduces a failure mode that assertion is blind to.
 * Split one clearing account into sixteen and every wrong-shard posting is still perfectly balanced —
 * a debit and a credit of equal size, Σ untouched, no negative user balance, nothing to alert on.
 * What changes is only <i>which</i> sub-account holds the money, and the platform finds out at
 * reconciliation, in production, with real customers' payments in the wrong bucket.
 *
 * <p>So this storm asserts the stronger, per-shard statement: <b>each shard ends holding exactly the
 * money that was posted into it</b>, entry-by-entry, and the sixteen of them plus the payers still sum
 * to the supply the test started with. Reversals are mixed in at random because a reversal is the only
 * operation that <i>takes</i> money out of a shard, and it is the operation that has to remember where
 * the money went — the storm computes each reversal's target the way the platform does, by reading the
 * account the debit recorded, never by asking the resolver a second time.
 */
@SpringBootTest
class ClearingShardInvariantsIT extends LocalStackTestBase {

    private static final String TABLE = "pix_ledger";

    private static final int SHARDS = 16;
    private static final int PAYERS = 4;
    private static final long PAYER_OPENING = 2_000_00L;
    private static final int THREADS = 16;
    private static final int SENDS_PER_THREAD = 10;
    private static final long MAX_AMOUNT = 500L;
    /** Fixed seed: the values are reproducible, the interleaving is not. */
    private static final long SEED = 20260828L;
    /** Roughly this share of the sends are then reversed, in the same thread that made them. */
    private static final int REVERSAL_PERCENT = 40;
    private static final int MAX_RESENDS = 20;

    @Autowired
    LedgerRepository repository;

    @Autowired
    DynamoDbClient dynamo;

    /**
     * One send, as the platform records it: the shard the debit actually credited is remembered, and
     * that remembered value — never a re-derivation — is what the reversal debits.
     */
    private record Parked(String txId, String payer, String clearingAccountId, long amountCents) {
    }

    @Test
    void everyShardHoldsExactlyItsOwnMoneyAfterAStormOfSendsAndReversals() throws Exception {
        // A private clearing map for this test. uniqueAccountId only appends, so every id still begins
        // with SPI_CLEARING and AccountPolicy still exempts the lot from the funds guard — which is what
        // makes a shard legitimately negative and the conservation claim a strong one.
        var clearing = new ClearingAccountResolver(
                LedgerAccountFixture.uniqueAccountId("SPI_CLEARING#it-shard"), SHARDS);
        for (String shard : clearing.allShards()) {
            LedgerAccountFixture.openAccount(dynamo, shard, 0L);
        }
        var payers = new ArrayList<String>(PAYERS);
        for (int i = 0; i < PAYERS; i++) {
            String payer = LedgerAccountFixture.uniqueAccountId("it-shard-payer-" + i);
            LedgerAccountFixture.openAccount(dynamo, payer, PAYER_OPENING);
            payers.add(payer);
        }

        var allAccounts = new ArrayList<>(payers);
        allAccounts.addAll(clearing.allShards());
        long supplyBefore = totalOf(allAccounts);

        // What SHOULD be in each shard when the dust settles, accumulated by the threads themselves.
        Map<String, AtomicLong> expectedPerShard = new ConcurrentHashMap<>();
        clearing.allShards().forEach(shard -> expectedPerShard.put(shard, new AtomicLong()));
        var reversalsDone = new AtomicLong();
        var sendsCommitted = new AtomicLong();

        var startedAt = Instant.parse("2026-08-28T09:00:00.000Z");
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        var rope = new CountDownLatch(1);
        var failures = Collections.synchronizedList(new ArrayList<Throwable>());

        for (int i = 0; i < THREADS; i++) {
            int threadIndex = i;
            pool.submit(() -> {
                var rng = new Random(SEED + threadIndex);
                try {
                    rope.await();
                    for (int s = 0; s < SENDS_PER_THREAD; s++) {
                        String payer = payers.get(rng.nextInt(payers.size()));
                        String txId = "it-shard-tx-" + threadIndex + "-" + s + "-" + System.nanoTime();
                        long amount = 1L + rng.nextLong(MAX_AMOUNT);

                        // THE outbound step, exactly as payment-service does it: resolve once, post,
                        // and remember the id that was posted.
                        String shard = clearing.shardFor(txId);
                        var parked = new Parked(txId, payer, shard, amount);
                        if (!post(new PostingCommand(txId, payer, shard, amount, "PIX_OUT", "shard storm"),
                                startedAt.plusMillis(threadIndex * 1000L + s))) {
                            continue;
                        }
                        sendsCommitted.incrementAndGet();
                        expectedPerShard.get(shard).addAndGet(amount);

                        if (rng.nextInt(100) < REVERSAL_PERCENT) {
                            // THE reversal, exactly as settlement-service does it: the target comes off
                            // the record. Asking clearing.shardFor(txId) here would pass this test and
                            // be the bug — see ReversalShardIT, which is built to catch precisely that.
                            var reversal = new PostingCommand(
                                    parked.txId() + "-rev", parked.clearingAccountId(), parked.payer(),
                                    parked.amountCents(), "PIX_REVERSAL", "shard storm reversal");
                            if (post(reversal, startedAt.plusMillis(threadIndex * 1000L + s).plusSeconds(1))) {
                                expectedPerShard.get(parked.clearingAccountId())
                                        .addAndGet(-parked.amountCents());
                                reversalsDone.incrementAndGet();
                            }
                        }
                    }
                } catch (Throwable t) {
                    failures.add(t);
                }
            });
        }

        rope.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.MINUTES)).isTrue();
        assertThat(failures).as("no thread died on an unexpected exception").isEmpty();

        // Vacuity guards: an all-refused storm would satisfy every assertion below and prove nothing.
        assertThat(sendsCommitted.get()).as("nothing committed, so nothing is proven").isPositive();
        assertThat(reversalsDone.get()).as("no reversal ran, so the compensating path is untested")
                .isPositive();
        assertThat(expectedPerShard.values().stream().filter(v -> v.get() != 0L).count())
                .as("the storm must actually spread over several shards").isGreaterThan(1L);

        // 1. PER SHARD — the assertion global conservation cannot make. Each sub-account holds exactly
        //    the money posted into it, and its own entry history says the same thing.
        for (String shard : clearing.allShards()) {
            assertThat(balanceOf(shard))
                    .as("shard %s holds exactly the money parked in it", shard)
                    .isEqualTo(expectedPerShard.get(shard).get());
            assertThat(balanceOf(shard))
                    .as("shard %s's balance agrees with its own entries", shard)
                    .isEqualTo(signedSumOf(shard));
        }

        // 2. THE LOGICAL POSITION — the sum over the shards is what the un-sharded account would have
        //    held. This is the claim the clearing-balance endpoint makes to the rest of the platform.
        long logicalClearing = clearing.allShards().stream().mapToLong(this::balanceOf).sum();
        assertThat(logicalClearing)
                .isEqualTo(expectedPerShard.values().stream().mapToLong(AtomicLong::get).sum());

        // 3. GLOBAL — money moved, none was created or destroyed. The weaker check, kept because it is
        //    the one that catches a leg written without its partner.
        assertThat(totalOf(allAccounts)).isEqualTo(supplyBefore);
        assertThat(allAccounts.stream().mapToLong(this::signedSumOf).sum()).isZero();

        // 4. No payer went negative — the funds guard still applies to them, shards or not.
        for (String payer : payers) {
            assertThat(balanceOf(payer)).as("payer %s never went below zero", payer).isNotNegative();
        }
    }

    /** {@code true} when the posting moved money (or had already moved it); {@code false} if refused. */
    private boolean post(PostingCommand command, Instant postedAt) throws InterruptedException {
        LedgerBusyException lastBusy = null;
        for (int resend = 1; resend <= MAX_RESENDS; resend++) {
            try {
                repository.post(command, postedAt);
                return true;
            } catch (InsufficientFundsException shortOfMoney) {
                return false;
            } catch (LedgerBusyException busy) {
                lastBusy = busy;
                Thread.sleep(ThreadLocalRandom.current().nextLong(10L, 60L));
            }
        }
        throw lastBusy;
    }

    private long balanceOf(String accountId) {
        return repository.getBalance(accountId).orElseThrow().balanceCents();
    }

    private long totalOf(List<String> accountIds) {
        return accountIds.stream().mapToLong(this::balanceOf).sum();
    }

    private long signedSumOf(String accountId) {
        return entriesOf(accountId).stream()
                .mapToLong(item -> Long.parseLong(item.get("amountCents").n()))
                .sum();
    }

    private List<Map<String, AttributeValue>> entriesOf(String accountId) {
        var items = new ArrayList<Map<String, AttributeValue>>();
        Map<String, AttributeValue> start = null;
        do {
            var request = QueryRequest.builder()
                    .tableName(TABLE)
                    .consistentRead(true)
                    .keyConditionExpression("pk = :account AND begins_with(sk, :entry)")
                    .expressionAttributeValues(Map.of(
                            ":account", AttributeValue.fromS("ACCOUNT#" + accountId),
                            ":entry", AttributeValue.fromS("ENTRY#")));
            if (start != null) {
                request.exclusiveStartKey(start);
            }
            var page = dynamo.query(request.build());
            items.addAll(page.items());
            start = page.hasLastEvaluatedKey() ? page.lastEvaluatedKey() : null;
        } while (start != null);
        return items;
    }

}
