package com.platinumcoin.pix.ledger.infra;

import com.platinumcoin.pix.common.testsupport.LocalStackTestBase;
import com.platinumcoin.pix.ledger.LedgerAccountFixture;
import com.platinumcoin.pix.ledger.domain.exception.InsufficientFundsException;
import com.platinumcoin.pix.ledger.domain.exception.LedgerAccountNotFoundException;
import com.platinumcoin.pix.ledger.domain.exception.PostingConflictException;
import com.platinumcoin.pix.ledger.domain.model.PostingCommand;
import com.platinumcoin.pix.ledger.domain.model.PostingResult;
import com.platinumcoin.pix.ledger.domain.port.LedgerRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The double-entry posting against a real DynamoDB — the step's central test, and the one that has to
 * be believed.
 *
 * <p>Each test gets its <b>own pair of ledger accounts</b> ({@link LedgerAccountFixture}) rather than
 * spending alice's seeded money: every {@code *IT} of this module shares one LocalStack container,
 * and the step-13 tests assert the seeded supply in absolute terms. A test that moved that money
 * would make the suite's outcome depend on class execution order — the kind of red nobody trusts.
 *
 * <p>Two properties are asserted on <b>every</b> failure path, and they are the point of the step:
 * the exception, and <b>zero writes</b> — no balance moved, no entry appended, no guard item left
 * behind. "Nothing was written" is what makes a refusal safe; an API that refuses but leaves a debit
 * behind is worse than one that accepts.
 *
 * <p>What is deliberately <b>not</b> here: concurrency. The parallel debit storm, the "exactly
 * ⌊balance/amount⌋ succeed" property and Σ-conservation under contention are step 15's suite, which
 * is a hand-written zone.
 */
@SpringBootTest
class LedgerPostingIT extends LocalStackTestBase {

    private static final String TABLE = "pix_ledger";
    private static final long OPENING_BALANCE = 1_000_000L;

    @Autowired
    LedgerRepository repository;

    @Autowired
    DynamoDbClient dynamo;

    private String payer;
    private String payee;
    private String txId;

    @BeforeEach
    void openAccounts() {
        payer = LedgerAccountFixture.uniqueAccountId("it-payer");
        payee = LedgerAccountFixture.uniqueAccountId("it-payee");
        txId = LedgerAccountFixture.uniqueAccountId("it-tx");
        LedgerAccountFixture.openAccount(dynamo, payer, OPENING_BALANCE);
        LedgerAccountFixture.openAccount(dynamo, payee, 0L);
    }

    /**
     * The happy path, and with it the invariant the whole platform exists to protect: the debit and
     * the credit are the same event. Both balances move, both legs are written, both versions are
     * bumped — and Σ over the two accounts is unchanged, because a posting <i>moves</i> money and
     * never creates or destroys it.
     */
    @Test
    void aPostingMovesBothBalancesWritesBothLegsAndConservesTheMoney() {
        PostingResult result = repository.post(
                new PostingCommand(txId, payer, payee, 12_550L, "PIX_INTERNAL", "rent"),
                Instant.parse("2026-08-03T10:15:30.123Z"));

        assertThat(result.replayed()).isFalse();
        assertThat(balanceOf(payer)).isEqualTo(OPENING_BALANCE - 12_550L);
        assertThat(balanceOf(payee)).isEqualTo(12_550L);
        assertThat(versionOf(payer)).isEqualTo(1L);
        assertThat(versionOf(payee)).isEqualTo(1L);
        // Money moved, none appeared or vanished.
        assertThat(balanceOf(payer) + balanceOf(payee)).isEqualTo(OPENING_BALANCE);

        // Both legs, found through GSI1 — the audit/reconciliation pattern the base table cannot
        // serve, since the two legs live in two different account partitions. Exactly two items:
        // the posting guard carries no gsi1pk on purpose, so "the legs of TX#t" stays literal.
        List<Map<String, AttributeValue>> legs = legsOf(txId);
        assertThat(legs).hasSize(2);
        assertThat(legs).anySatisfy(leg -> {
            assertThat(leg.get("pk").s()).isEqualTo("ACCOUNT#" + payer);
            assertThat(leg.get("direction").s()).isEqualTo("DEBIT");
            assertThat(leg.get("amountCents").n()).isEqualTo("-12550");
            assertThat(leg.get("sk").s()).isEqualTo("ENTRY#2026-08-03T10:15:30.123Z#" + txId);
            assertThat(leg.get("counterpartAccountId").s()).isEqualTo(payee);
            assertThat(leg.get("entryType").s()).isEqualTo("PIX_INTERNAL");
        });
        assertThat(legs).anySatisfy(leg -> {
            assertThat(leg.get("pk").s()).isEqualTo("ACCOUNT#" + payee);
            assertThat(leg.get("direction").s()).isEqualTo("CREDIT");
            assertThat(leg.get("amountCents").n()).isEqualTo("12550");
            assertThat(leg.get("counterpartAccountId").s()).isEqualTo(payer);
        });
        // Σ of the two legs is zero — the double entry, checkable on the items themselves.
        assertThat(legs.stream().mapToLong(leg -> Long.parseLong(leg.get("amountCents").n())).sum())
                .isZero();
        assertThat(postingGuardOf(txId)).isNotEmpty();
    }

    /**
     * The no-negative-balance guard, exercised where it lives: inside the transaction. The important
     * half of this test is not the exception — it is that nothing at all was written, which is only
     * true because the check and the debit are one operation.
     */
    @Test
    void anOverdraftIsRefusedInsideTheTransactionAndWritesNothing() {
        assertThatThrownBy(() -> repository.post(
                new PostingCommand(txId, payer, payee, OPENING_BALANCE + 1, "PIX_INTERNAL", "too much"),
                Instant.parse("2026-08-03T11:00:00.000Z")))
                .isInstanceOf(InsufficientFundsException.class)
                .satisfies(thrown -> assertThat(((InsufficientFundsException) thrown).availableCents())
                        .isEqualTo(OPENING_BALANCE));

        assertThat(balanceOf(payer)).isEqualTo(OPENING_BALANCE);
        assertThat(balanceOf(payee)).isZero();
        // No partial state: not even the version counter of the debtor moved…
        assertThat(versionOf(payer)).isZero();
        // …and no orphan leg or guard was left behind.
        assertThat(legsOf(txId)).isEmpty();
        assertThat(postingGuardOf(txId)).isEmpty();
    }

    /** Spending the balance to the last cent is allowed; the guard is {@code >=}, not {@code >}. */
    @Test
    void spendingTheExactBalanceIsAllowedAndLeavesZero() {
        repository.post(new PostingCommand(txId, payer, payee, OPENING_BALANCE, "PIX_INTERNAL", "all of it"),
                Instant.parse("2026-08-03T11:30:00.000Z"));

        assertThat(balanceOf(payer)).isZero();
        assertThat(balanceOf(payee)).isEqualTo(OPENING_BALANCE);
    }

    /**
     * <b>Idempotency by txId, with the clock moved forward between the attempts.</b> This is the
     * retry a caller actually makes: the first response was lost, so the same {@code txId} is sent
     * again — and lands at a different instant. Without the {@code TX#<txId>/POSTING} guard the two
     * ENTRY sort keys would differ, {@code attribute_not_exists} would pass, and the payer would be
     * debited twice. The assertion that matters is the balance: money moved exactly once.
     */
    @Test
    void replayingATxIdAtALaterInstantReturnsTheStoredPostingAndMovesMoneyOnlyOnce() {
        PostingCommand command = new PostingCommand(txId, payer, payee, 5_000L, "PIX_INTERNAL", "rent");

        PostingResult first = repository.post(command, Instant.parse("2026-08-03T12:00:00.000Z"));
        PostingResult retry = repository.post(command, Instant.parse("2026-08-03T12:00:07.500Z"));

        assertThat(first.replayed()).isFalse();
        assertThat(retry.replayed()).isTrue();
        // The reply describes when the money moved, not when the retry arrived.
        assertThat(retry.postedAt()).isEqualTo(first.postedAt());
        assertThat(balanceOf(payer)).isEqualTo(OPENING_BALANCE - 5_000L);
        assertThat(balanceOf(payee)).isEqualTo(5_000L);
        assertThat(versionOf(payer)).isEqualTo(1L);
        // One posting ⇒ one pair of legs, at the first instant only.
        assertThat(legsOf(txId)).hasSize(2);
        assertThat(legsOf(txId)).allSatisfy(leg ->
                assertThat(leg.get("sk").s()).isEqualTo("ENTRY#2026-08-03T12:00:00.000Z#" + txId));
    }

    /**
     * The same identity for different money is refused rather than resolved. Answering "already done"
     * would swallow this payment; posting it would double-spend the first one. 409 is the only answer
     * that loses nothing.
     */
    @Test
    void reusingATxIdForADifferentAmountIsRefusedAndWritesNothingNew() {
        repository.post(new PostingCommand(txId, payer, payee, 1_000L, "PIX_INTERNAL", "first"),
                Instant.parse("2026-08-03T13:00:00.000Z"));

        assertThatThrownBy(() -> repository.post(
                new PostingCommand(txId, payer, payee, 7_777L, "PIX_INTERNAL", "different money"),
                Instant.parse("2026-08-03T13:00:01.000Z")))
                .isInstanceOf(PostingConflictException.class)
                .hasMessageContaining(txId);

        assertThat(balanceOf(payer)).isEqualTo(OPENING_BALANCE - 1_000L);
        assertThat(balanceOf(payee)).isEqualTo(1_000L);
        assertThat(legsOf(txId)).hasSize(2);
    }

    /**
     * A different {@code description} is not different money (see
     * {@code PostingCommand#movesTheSameMoneyAs}): a caller that regenerates a human label on retry
     * must still get its replay, or it would be pushed towards minting a new txId — the one reaction
     * that actually double-spends.
     */
    @Test
    void aReplayWithADifferentDescriptionIsStillAReplay() {
        repository.post(new PostingCommand(txId, payer, payee, 300L, "PIX_INTERNAL", "rent"),
                Instant.parse("2026-08-03T14:00:00.000Z"));

        PostingResult retry = repository.post(
                new PostingCommand(txId, payer, payee, 300L, "PIX_INTERNAL", "rent (retry #2)"),
                Instant.parse("2026-08-03T14:00:02.000Z"));

        assertThat(retry.replayed()).isTrue();
        assertThat(balanceOf(payer)).isEqualTo(OPENING_BALANCE - 300L);
    }

    /**
     * {@code AccountPolicy} against the real table, on a clearing <b>shard</b> ({@code SPI_CLEARING#…},
     * the shape step 52 introduces): it opens at zero and is debited below it on purpose — its balance
     * is an inter-bank position, not a wallet. If the prefix exemption ever silently disappeared, this
     * posting would start returning 422 and every outbound settlement of Sprint 6 would stall.
     */
    @Test
    void aSystemAccountMayBeDebitedIntoTheNegative() {
        String clearingShard = LedgerAccountFixture.uniqueAccountId("SPI_CLEARING#it");
        LedgerAccountFixture.openAccount(dynamo, clearingShard, 0L);

        repository.post(new PostingCommand(txId, clearingShard, payee, 250L, "PIX_IN", "inbound"),
                Instant.parse("2026-08-03T15:00:00.000Z"));

        assertThat(balanceOf(clearingShard)).isEqualTo(-250L);
        assertThat(balanceOf(payee)).isEqualTo(250L);
        // Still conserved: a negative system balance is the counterpart of the credited money.
        assertThat(balanceOf(clearingShard) + balanceOf(payee)).isZero();
    }

    @Test
    void anUnknownDebitAccountIsANotFoundAndWritesNothing() {
        assertThatThrownBy(() -> repository.post(
                new PostingCommand(txId, "acc-does-not-exist", payee, 100L, "PIX_INTERNAL", "ghost payer"),
                Instant.parse("2026-08-03T16:00:00.000Z")))
                .isInstanceOf(LedgerAccountNotFoundException.class)
                .hasMessageContaining("acc-does-not-exist");

        assertThat(balanceOf(payee)).isZero();
        assertThat(legsOf(txId)).isEmpty();
        assertThat(postingGuardOf(txId)).isEmpty();
    }

    /**
     * The credit leg's {@code attribute_exists} guard, and the failure it prevents: {@code UpdateItem}
     * is an upsert, so without the condition a typo'd payee would <i>create</i> a ledger account and
     * the money would land somewhere nobody owns.
     */
    @Test
    void anUnknownCreditAccountIsANotFoundAndCreatesNoAccount() {
        assertThatThrownBy(() -> repository.post(
                new PostingCommand(txId, payer, "acc-typo", 100L, "PIX_INTERNAL", "ghost payee"),
                Instant.parse("2026-08-03T17:00:00.000Z")))
                .isInstanceOf(LedgerAccountNotFoundException.class)
                .hasMessageContaining("acc-typo");

        assertThat(balanceOf(payer)).isEqualTo(OPENING_BALANCE);
        assertThat(repository.getBalance("acc-typo")).isEmpty();
    }

    // ── raw reads, on purpose ───────────────────────────────────────────────────────────────────
    // These assertions look past the port at the stored items, because "the balance changed" is not
    // the same claim as "the two legs are on the table with the right signs and keys".

    private long balanceOf(String accountId) {
        return repository.getBalance(accountId).orElseThrow().balanceCents();
    }

    private long versionOf(String accountId) {
        return repository.getBalance(accountId).orElseThrow().version();
    }

    private List<Map<String, AttributeValue>> legsOf(String transactionId) {
        return dynamo.query(QueryRequest.builder()
                .tableName(TABLE)
                .indexName("gsi1")
                .keyConditionExpression("gsi1pk = :tx")
                .expressionAttributeValues(Map.of(":tx", AttributeValue.fromS("TX#" + transactionId)))
                .build()).items();
    }

    private Map<String, AttributeValue> postingGuardOf(String transactionId) {
        return dynamo.getItem(request -> request
                .tableName(TABLE)
                .consistentRead(true)
                .key(Map.of(
                        "pk", AttributeValue.fromS("TX#" + transactionId),
                        "sk", AttributeValue.fromS("POSTING")))).item();
    }
}
