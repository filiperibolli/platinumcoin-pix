package com.platinumcoin.pix.ledger;

import java.util.Map;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Disposable ledger accounts for the tests that <b>move money</b>.
 *
 * <p>Every {@code *IT} in this module shares one LocalStack container (the singleton-container
 * pattern of {@code LocalStackTestBase}), and the step-13 tests assert the seeded money supply in
 * <i>absolute</i> terms — alice holds exactly R$ 10,000.00, {@code SEED} exactly −R$ 20,000.00,
 * Σ = 0. Those assertions are the point of that step: they prove the shipped seed script, not merely
 * some balance. So the posting tests must not spend that money, or the suite's result would depend on
 * the order the classes happen to run in.
 *
 * <p>Hence a fixture account per test: created here with a plain {@code PutItem}, named uniquely, and
 * never asserted by anyone else. The money it holds is invisible to the seeded-supply assertions,
 * which sum a fixed list of four accounts.
 */
public final class LedgerAccountFixture {

    private LedgerAccountFixture() {
    }

    /** A unique account id for one test, e.g. {@code it-payer-1830…}. */
    public static String uniqueAccountId(String prefix) {
        return prefix + "-" + System.nanoTime();
    }

    /**
     * Create the BALANCE item of a fresh ledger account. Deliberately a raw put and not a posting:
     * a test needs its starting balance to exist before the operation under test runs, and the ledger
     * has no "open account" operation — the same way {@code 05-seed-ledger.sh} creates the platform's
     * money supply.
     */
    public static void openAccount(DynamoDbClient dynamo, String accountId, long balanceCents) {
        dynamo.putItem(request -> request
                .tableName("pix_ledger")
                .item(Map.of(
                        "pk", AttributeValue.fromS("ACCOUNT#" + accountId),
                        "sk", AttributeValue.fromS("BALANCE"),
                        "balanceCents", AttributeValue.fromN(Long.toString(balanceCents)),
                        "version", AttributeValue.fromN("0"),
                        "updatedAt", AttributeValue.fromS("2026-08-03T00:00:00.000Z"))));
    }
}
