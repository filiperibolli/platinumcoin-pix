package com.platinumcoin.pix.ledger.infra;

import com.platinumcoin.pix.ledger.domain.exception.InsufficientFundsException;
import com.platinumcoin.pix.ledger.domain.exception.LedgerAccountNotFoundException;
import com.platinumcoin.pix.ledger.domain.exception.LedgerBusyException;
import com.platinumcoin.pix.ledger.domain.exception.PostingConflictException;
import com.platinumcoin.pix.ledger.domain.model.PostingCommand;
import com.platinumcoin.pix.ledger.domain.model.PostingResult;
import com.platinumcoin.pix.ledger.domain.service.AccountPolicy;
import com.platinumcoin.pix.ledger.infra.persistence.DynamoLedgerRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.ReturnValuesOnConditionCheckFailure;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.Update;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Two things only this test can prove, and both are money-critical.
 *
 * <p><b>1. The exact shape of the transaction.</b> An IT sees the <i>effect</i> of a posting — the
 * balances moved — and a correct effect is produced by several incorrect transactions: one whose
 * debit carries no {@code balanceCents >= :amount} condition looks perfectly healthy until the day
 * an account is overdrawn. The conditions are the product here, so they are asserted on the request
 * itself, mechanically, where a refactor cannot quietly drop one.
 *
 * <p><b>2. The reading of {@code cancellationReasons()}.</b> DynamoDB reports a cancelled
 * transaction as a positional list, one reason per item, and *which* condition failed is the whole
 * difference between "you have no money" (422), "that account does not exist" (404), "you already
 * posted this" (200) and "try again" (503). Provoking each of those against a real emulator would
 * mean racing it; here each is a constructed exception, so every branch is covered in milliseconds.
 *
 * <p>The stub is hand-written for the same reason {@code DynamoLedgerRepositoryTest}'s is: the SDK
 * declares its operations as {@code default} methods, so overriding the two calls under test reads
 * better than teaching a mocking framework to pick between overloads.
 */
class DynamoLedgerPostingTest {

    private static final Instant POSTED_AT = Instant.parse("2026-08-03T10:15:30.123Z");
    private static final PostingCommand TRANSFER =
            new PostingCommand("tx-9f1c", "acc-001", "acc-002", 12_550L, "PIX_INTERNAL", "rent");

    private final CapturingDynamoDbClient dynamo = new CapturingDynamoDbClient();
    private final DynamoLedgerRepository repository =
            new DynamoLedgerRepository(dynamo, new AccountPolicy());

    // ── the shape of the transaction ────────────────────────────────────────────────────────────

    @Test
    void aPostingIsOneTransactionOfFiveItems() {
        repository.post(TRANSFER, POSTED_AT);

        // Four writes from docs/data-model.md §3 plus the posting guard: debit balance, credit
        // balance, debit entry, credit entry, TX#<txId>/POSTING. All or nothing, by construction.
        assertThat(dynamo.capturedTransaction().transactItems()).hasSize(5);
    }

    @Test
    void theDebitCarriesTheNoNegativeBalanceConditionInsideTheTransaction() {
        repository.post(TRANSFER, POSTED_AT);

        Update debit = dynamo.capturedTransaction().transactItems().get(0).update();
        assertThat(debit.tableName()).isEqualTo("pix_ledger");
        assertThat(debit.key()).isEqualTo(Map.of(
                "pk", AttributeValue.fromS("ACCOUNT#acc-001"),
                "sk", AttributeValue.fromS("BALANCE")));
        // The one line the whole platform rests on: the guard is a condition of the write, not a
        // check performed before it, so no interleaving can slip between reading and deciding.
        assertThat(debit.conditionExpression()).isEqualTo("attribute_exists(pk) AND balanceCents >= :amount");
        assertThat(debit.updateExpression())
                .isEqualTo("SET balanceCents = balanceCents - :amount, version = version + :one, updatedAt = :now");
        assertThat(debit.expressionAttributeValues().get(":amount")).isEqualTo(AttributeValue.fromN("12550"));
        // Without ALL_OLD a failed condition is anonymous: "no such account" and "not enough money"
        // would be the same event, and the caller would get one of them at random.
        assertThat(debit.returnValuesOnConditionCheckFailure())
                .isEqualTo(ReturnValuesOnConditionCheckFailure.ALL_OLD);
    }

    @Test
    void theCreditRequiresTheAccountToExistSoAnUpdateCanNeverCreateOne() {
        repository.post(TRANSFER, POSTED_AT);

        Update credit = dynamo.capturedTransaction().transactItems().get(1).update();
        assertThat(credit.key()).isEqualTo(Map.of(
                "pk", AttributeValue.fromS("ACCOUNT#acc-002"),
                "sk", AttributeValue.fromS("BALANCE")));
        // UpdateItem is an upsert: without attribute_exists, crediting a typo'd account id would
        // silently mint a brand-new ledger account and Σ balances would still look fine.
        assertThat(credit.conditionExpression()).isEqualTo("attribute_exists(pk)");
        assertThat(credit.updateExpression())
                .isEqualTo("SET balanceCents = balanceCents + :amount, version = version + :one, updatedAt = :now");
    }

    /**
     * The sign convention of {@code Direction}, asserted where it is written: the two legs are equal
     * and opposite, so Σ {@code amountCents} of a posting is zero — the property that makes
     * "Σ entries == Σ balances" checkable over the whole table (step 15).
     */
    @Test
    void theTwoEntriesAreEqualAndOppositeAndLandInTheirOwnAccountPartitions() {
        repository.post(TRANSFER, POSTED_AT);

        Put debitEntry = dynamo.capturedTransaction().transactItems().get(2).put();
        Put creditEntry = dynamo.capturedTransaction().transactItems().get(3).put();

        assertThat(debitEntry.item().get("pk")).isEqualTo(AttributeValue.fromS("ACCOUNT#acc-001"));
        assertThat(debitEntry.item().get("direction")).isEqualTo(AttributeValue.fromS("DEBIT"));
        assertThat(debitEntry.item().get("amountCents")).isEqualTo(AttributeValue.fromN("-12550"));
        assertThat(debitEntry.item().get("counterpartAccountId")).isEqualTo(AttributeValue.fromS("acc-002"));

        assertThat(creditEntry.item().get("pk")).isEqualTo(AttributeValue.fromS("ACCOUNT#acc-002"));
        assertThat(creditEntry.item().get("direction")).isEqualTo(AttributeValue.fromS("CREDIT"));
        assertThat(creditEntry.item().get("amountCents")).isEqualTo(AttributeValue.fromN("12550"));
        assertThat(creditEntry.item().get("counterpartAccountId")).isEqualTo(AttributeValue.fromS("acc-001"));

        // Both legs are reachable as one transaction through GSI1 — the audit/reconciliation pattern
        // the base table cannot serve, because the legs live in two different partitions.
        assertThat(debitEntry.item().get("gsi1pk")).isEqualTo(AttributeValue.fromS("TX#tx-9f1c"));
        assertThat(creditEntry.item().get("gsi1pk")).isEqualTo(AttributeValue.fromS("TX#tx-9f1c"));
    }

    /**
     * Fixed-width milliseconds, and the reason is subtle enough to deserve its own test:
     * {@code Instant.toString()} omits trailing zeros, so an entry at exactly 10:15:30 would render
     * {@code 10:15:30Z} while one 500 ms later renders {@code 10:15:30.500Z} — and {@code 'Z'} (0x5A)
     * sorts <i>after</i> {@code '.'} (0x2E). The sort keys would then be lexicographically out of
     * chronological order, and the newest-first statement of step 16, which relies on nothing but
     * that ordering, would silently return the wrong page.
     */
    @Test
    void entrySortKeysAreTimestampPrefixedWithFixedWidthMillisecondsSoTheySortChronologically() {
        repository.post(TRANSFER, Instant.parse("2026-08-03T10:15:30Z"));
        String onTheSecond = sortKeyOfDebitEntry();

        repository.post(TRANSFER, Instant.parse("2026-08-03T10:15:30.500Z"));
        String halfASecondLater = sortKeyOfDebitEntry();

        assertThat(onTheSecond).isEqualTo("ENTRY#2026-08-03T10:15:30.000Z#tx-9f1c");
        assertThat(halfASecondLater).isEqualTo("ENTRY#2026-08-03T10:15:30.500Z#tx-9f1c");
        assertThat(onTheSecond).isLessThan(halfASecondLater);
    }

    @Test
    void theGuardItemIsKeyedOnlyByTxIdAndStaysOutOfTheIndexOfLegs() {
        repository.post(TRANSFER, POSTED_AT);

        Put guard = dynamo.capturedTransaction().transactItems().get(4).put();
        // Keyed by txId alone — deliberately NOT by timestamp. That is what makes a retry whose clock
        // reading differs collide with the original posting instead of writing a second one.
        assertThat(guard.item().get("pk")).isEqualTo(AttributeValue.fromS("TX#tx-9f1c"));
        assertThat(guard.item().get("sk")).isEqualTo(AttributeValue.fromS("POSTING"));
        assertThat(guard.conditionExpression()).isEqualTo("attribute_not_exists(pk)");
        assertThat(guard.returnValuesOnConditionCheckFailure())
                .isEqualTo(ReturnValuesOnConditionCheckFailure.ALL_OLD);
        // It carries the command so a replay can be answered from the cancellation itself…
        assertThat(guard.item().get("amountCents")).isEqualTo(AttributeValue.fromN("12550"));
        assertThat(guard.item().get("debitAccount")).isEqualTo(AttributeValue.fromS("acc-001"));
        assertThat(guard.item().get("creditAccount")).isEqualTo(AttributeValue.fromS("acc-002"));
        assertThat(guard.item().get("postedAt")).isEqualTo(AttributeValue.fromS("2026-08-03T10:15:30.123Z"));
        // …and no gsi1pk, so "give me both legs of TX#t" keeps returning exactly two items.
        assertThat(guard.item()).doesNotContainKey("gsi1pk");
    }

    @Test
    void theEntryPutsRefuseToOverwriteAnExistingEntry() {
        repository.post(TRANSFER, POSTED_AT);

        assertThat(dynamo.capturedTransaction().transactItems().get(2).put().conditionExpression())
                .isEqualTo("attribute_not_exists(pk)");
        assertThat(dynamo.capturedTransaction().transactItems().get(3).put().conditionExpression())
                .isEqualTo("attribute_not_exists(pk)");
    }

    /**
     * The exemption of {@code AccountPolicy}, asserted end to end through the adapter: a system
     * account is debited without the funds condition (it is negative by construction), but still
     * with {@code attribute_exists}, so a typo'd system id is a 404 rather than a new account.
     */
    @Test
    void systemAccountsAreDebitedWithoutTheFundsConditionButMustStillExist() {
        repository.post(new PostingCommand("tx-seed", "SEED", "acc-001", 1_000_000L, "SEED_FUNDING", ""),
                POSTED_AT);

        assertThat(dynamo.capturedTransaction().transactItems().get(0).update().conditionExpression())
                .isEqualTo("attribute_exists(pk)");
    }

    // ── the reading of the cancellation reasons ─────────────────────────────────────────────────

    @Test
    void aFailedFundsConditionBecomesInsufficientFunds() {
        dynamo.failWith(cancellation(
                conditionFailed(Map.of("balanceCents", AttributeValue.fromN("500"))),
                none(), none(), none(), none()));

        assertThatThrownBy(() -> repository.post(TRANSFER, POSTED_AT))
                .isInstanceOf(InsufficientFundsException.class)
                .satisfies(thrown -> {
                    InsufficientFundsException ex = (InsufficientFundsException) thrown;
                    assertThat(ex.accountId()).isEqualTo("acc-001");
                    assertThat(ex.availableCents()).isEqualTo(500L);
                    assertThat(ex.requestedCents()).isEqualTo(12_550L);
                });
    }

    /**
     * Same failed condition, no item returned: the debtor's BALANCE item does not exist at all. The
     * two cases are indistinguishable without the ALL_OLD payload, which is precisely why it is
     * requested — a caller told "insufficient funds" for an account that was never opened would go
     * looking for money that was never there.
     */
    @Test
    void aFailedDebitConditionWithNoItemBecomesAccountNotFound() {
        dynamo.failWith(cancellation(conditionFailed(Map.of()), none(), none(), none(), none()));
        dynamo.respondWith(GetItemResponse.builder().build());

        assertThatThrownBy(() -> repository.post(TRANSFER, POSTED_AT))
                .isInstanceOf(LedgerAccountNotFoundException.class)
                .hasMessageContaining("acc-001");
    }

    @Test
    void aFailedCreditConditionBecomesAccountNotFoundForThePayee() {
        dynamo.failWith(cancellation(none(), conditionFailed(Map.of()), none(), none(), none()));

        assertThatThrownBy(() -> repository.post(TRANSFER, POSTED_AT))
                .isInstanceOf(LedgerAccountNotFoundException.class)
                .hasMessageContaining("acc-002");
    }

    @Test
    void aFailedGuardWithTheSameCommandIsAnIdempotentReplay() {
        dynamo.failWith(cancellation(none(), none(), none(), none(), conditionFailed(storedPosting("12550"))));

        PostingResult result = repository.post(TRANSFER, Instant.parse("2026-08-03T23:59:59.999Z"));

        assertThat(result.replayed()).isTrue();
        // The stored instant wins: this is when the money actually moved, not when the retry arrived.
        assertThat(result.postedAt()).isEqualTo(POSTED_AT);
        assertThat(result.command().amountCents()).isEqualTo(12_550L);
    }

    @Test
    void aFailedGuardWithDifferentMoneyIsAConflict() {
        dynamo.failWith(cancellation(none(), none(), none(), none(), conditionFailed(storedPosting("999"))));

        assertThatThrownBy(() -> repository.post(TRANSFER, POSTED_AT))
                .isInstanceOf(PostingConflictException.class)
                .hasMessageContaining("tx-9f1c");
    }

    /**
     * Idempotency outranks every other reason. A replayed posting that would <i>also</i> now be short
     * of funds (the money has since been spent) is still a replay: the posting it names already
     * committed, and re-answering 422 would tell the caller a payment failed that in fact succeeded.
     */
    @Test
    void theReplayVerdictWinsOverAFundsFailureInTheSameCancellation() {
        dynamo.failWith(cancellation(
                conditionFailed(Map.of("balanceCents", AttributeValue.fromN("0"))),
                none(), none(), none(), conditionFailed(storedPosting("12550"))));

        assertThat(repository.post(TRANSFER, POSTED_AT).replayed()).isTrue();
    }

    /**
     * An entry already exists for this txId but the guard item does not — the shape the step-12 seed
     * has, its postings predating the guard. Refusing is the conservative reading: the alternative is
     * a second set of entries under an identity the ledger has already used.
     */
    @Test
    void anExistingEntryWithoutAGuardItemIsAConflictNotASecondPosting() {
        dynamo.failWith(cancellation(none(), none(), conditionFailed(Map.of()), none(), none()));

        assertThatThrownBy(() -> repository.post(TRANSFER, POSTED_AT))
                .isInstanceOf(PostingConflictException.class);
    }

    @Test
    void aTransactionConflictIsRetriedAndThenGivenUpOnAsBusy() {
        dynamo.failWith(cancellation(transactionConflict(), none(), none(), none(), none()));

        assertThatThrownBy(() -> repository.post(TRANSFER, POSTED_AT))
                .isInstanceOf(LedgerBusyException.class);
        // Bounded: contention must not turn into an unbounded stall of the calling thread.
        assertThat(dynamo.transactionAttempts()).isEqualTo(3);
    }

    @Test
    void aTransactionConflictThatClearsOnRetryCommitsNormally() {
        dynamo.failWith(cancellation(transactionConflict(), none(), none(), none(), none()));
        dynamo.succeedFromAttempt(2);

        PostingResult result = repository.post(TRANSFER, POSTED_AT);

        assertThat(result.replayed()).isFalse();
        assertThat(dynamo.transactionAttempts()).isEqualTo(2);
        // The retry re-sends the *same* request: same timestamp, therefore the same entry keys — a
        // retry can never become a second posting, even before the guard item is consulted.
        assertThat(sortKeyOfDebitEntry()).isEqualTo("ENTRY#2026-08-03T10:15:30.123Z#tx-9f1c");
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    private String sortKeyOfDebitEntry() {
        return dynamo.capturedTransaction().transactItems().get(2).put().item().get("sk").s();
    }

    private static Map<String, AttributeValue> storedPosting(String amountCents) {
        return Map.of(
                "pk", AttributeValue.fromS("TX#tx-9f1c"),
                "sk", AttributeValue.fromS("POSTING"),
                "txId", AttributeValue.fromS("tx-9f1c"),
                "debitAccount", AttributeValue.fromS("acc-001"),
                "creditAccount", AttributeValue.fromS("acc-002"),
                "amountCents", AttributeValue.fromN(amountCents),
                "entryType", AttributeValue.fromS("PIX_INTERNAL"),
                "description", AttributeValue.fromS("rent"),
                "postedAt", AttributeValue.fromS("2026-08-03T10:15:30.123Z"));
    }

    private static TransactionCanceledException cancellation(CancellationReason... reasons) {
        return TransactionCanceledException.builder()
                .message("Transaction cancelled, please refer cancellation reasons for specific reasons")
                .cancellationReasons(reasons)
                .build();
    }

    private static CancellationReason none() {
        return CancellationReason.builder().code("None").build();
    }

    private static CancellationReason conditionFailed(Map<String, AttributeValue> item) {
        return CancellationReason.builder().code("ConditionalCheckFailed").item(item).build();
    }

    private static CancellationReason transactionConflict() {
        return CancellationReason.builder().code("TransactionConflict")
                .message("Transaction is ongoing for the item").build();
    }

    /** Records every transaction the adapter sends and answers with whatever the test programmed. */
    private static final class CapturingDynamoDbClient implements DynamoDbClient {

        private final List<TransactWriteItemsRequest> transactions = new ArrayList<>();
        private RuntimeException failure;
        private int succeedFromAttempt = Integer.MAX_VALUE;
        private GetItemResponse getItemResponse = GetItemResponse.builder().build();

        void failWith(RuntimeException failure) {
            this.failure = failure;
        }

        void succeedFromAttempt(int attempt) {
            this.succeedFromAttempt = attempt;
        }

        void respondWith(GetItemResponse response) {
            this.getItemResponse = response;
        }

        TransactWriteItemsRequest capturedTransaction() {
            assertThat(transactions).as("the adapter never sent a transaction").isNotEmpty();
            return transactions.get(transactions.size() - 1);
        }

        int transactionAttempts() {
            return transactions.size();
        }

        @Override
        public TransactWriteItemsResponse transactWriteItems(TransactWriteItemsRequest request) {
            transactions.add(request);
            if (failure != null && transactions.size() < succeedFromAttempt) {
                throw failure;
            }
            return TransactWriteItemsResponse.builder().build();
        }

        @Override
        public GetItemResponse getItem(Consumer<GetItemRequest.Builder> getItemRequest) {
            return getItemResponse;
        }

        @Override
        public String serviceName() {
            return "dynamodb-test-stub";
        }

        @Override
        public void close() {
            // Nothing to release: this stub holds no connection.
        }
    }
}
