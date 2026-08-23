package com.platinumcoin.pix.payment.infra;

import com.platinumcoin.pix.common.testsupport.LocalStackTestBase;
import com.platinumcoin.pix.payment.domain.model.IdempotencyRecord;
import com.platinumcoin.pix.payment.domain.model.IdempotencyStatus;
import com.platinumcoin.pix.payment.domain.port.IdempotencyRepository;
import com.platinumcoin.pix.payment.support.PaymentTestSupport;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-0014 proven on the storage itself, over the real {@code pix_idempotency} table (LocalStack):
 * the conditional write that wins a key <b>also writes the identity the money will carry</b>, a
 * re-claim cannot rename that money, and the TTL recycles a key only when its operation actually
 * finished. These are properties of the two condition expressions, so they are asserted here rather
 * than through the fake — a fake can only mirror what the expressions were believed to say.
 *
 * <p>Each test uses its own key so the shared singleton container needs no cleanup between tests.
 */
@SpringBootTest
@Import(PaymentTestSupport.class)
class DynamoIdempotencyRepositoryIT extends LocalStackTestBase {

    private static final String ACCOUNT = "acc-identity";
    private static final String HASH = "sha256:identity-fixture";

    @Autowired
    IdempotencyRepository repository;

    @Autowired
    DynamoDbClient dynamo;

    @Test
    void claimPersistsTheIdentity() {
        String key = "claim-" + java.util.UUID.randomUUID();
        Instant now = Instant.now();

        assertThat(repository.claim(ACCOUNT, key, HASH, "tx-abc", "E12345678202607021234CLAIMED000", now))
                .isTrue();

        // Read the raw item back: the identity has to be ON the claim item, not merely in the caller's
        // heap, or a crash right after this line leaves the money nameless.
        Map<String, AttributeValue> item = item(ACCOUNT, key);
        assertThat(item.get("txId").s()).isEqualTo("tx-abc");
        assertThat(item.get("endToEndId").s()).isEqualTo("E12345678202607021234CLAIMED000");
        assertThat(item.get("status").s()).isEqualTo(IdempotencyStatus.CLAIMED.name());
    }

    @Test
    void reclaimPreservesTheIdentity() {
        String key = "reclaim-" + java.util.UUID.randomUUID();
        Instant claimedAt = Instant.now().minus(Duration.ofMinutes(5));

        assertThat(repository.claim(ACCOUNT, key, HASH, "tx-keepme", "E12345678202607021234KEEPME0000", claimedAt))
                .isTrue();
        Map<String, AttributeValue> before = item(ACCOUNT, key);

        Instant now = Instant.now();
        assertThat(repository.reclaim(ACCOUNT, key, "sha256:new-hash", claimedAt, now)).isTrue();

        Map<String, AttributeValue> after = item(ACCOUNT, key);
        // Byte-identical: a re-claim that could rename the money is the double-debit ADR-0014 closes.
        assertThat(after.get("txId").s()).isEqualTo(before.get("txId").s());
        assertThat(after.get("endToEndId").s()).isEqualTo(before.get("endToEndId").s());
        // Only the staleness stamps moved (plus the hash the caller explicitly re-asserted).
        assertThat(after.get("claimedAt").s()).isEqualTo(now.toString());
        assertThat(after.get("claimedAt").s()).isNotEqualTo(before.get("claimedAt").s());
        assertThat(Long.parseLong(after.get("expiresAt").n()))
                .isGreaterThan(Long.parseLong(before.get("expiresAt").n()));
        assertThat(after.get("status").s()).isEqualTo(IdempotencyStatus.CLAIMED.name());
    }

    @Test
    void expiredTerminalRecordIsReclaimable() {
        String key = "expired-done-" + java.util.UUID.randomUUID();
        Instant now = Instant.now();
        // The legitimate case ADR-0002 allows: the 24h window closed on a payment that finished, so the
        // key value is free again. The new TTL rule must not break it.
        plant(key, IdempotencyStatus.COMPLETED, "tx-old", now.minus(Duration.ofDays(2)), now.minus(Duration.ofDays(1)));

        assertThat(repository.claim(ACCOUNT, key, HASH, "tx-new", "E12345678202607021234FRESHID000", now))
                .isTrue();

        // A genuinely new payment, with its own identity — not a resurrection of the old one.
        assertThat(item(ACCOUNT, key).get("txId").s()).isEqualTo("tx-new");
    }

    @Test
    void expiredNonTerminalRecordIsNotReclaimable() {
        String key = "expired-stranded-" + java.util.UUID.randomUUID();
        Instant now = Instant.now();
        // An unresolved money operation past its window. The other half of the same rule: the TTL may
        // recycle a finished key, never a live money identity.
        plant(key, IdempotencyStatus.POSTED, "tx-stranded", now.minus(Duration.ofDays(2)), now.minus(Duration.ofDays(1)));

        assertThat(repository.claim(ACCOUNT, key, HASH, "tx-would-be-second", "E12345678202607021234SECOND0000", now))
                .isFalse();

        // Untouched: the stranded operation keeps its name, so a human can still find the money.
        assertThat(item(ACCOUNT, key).get("txId").s()).isEqualTo("tx-stranded");

        // And the use case can see it — get() reports expired records rather than hiding them.
        IdempotencyRecord record = repository.get(ACCOUNT, key).orElseThrow();
        assertThat(record.expired(now)).isTrue();
        assertThat(record.status().terminal()).isFalse();
        assertThat(record.txId()).isEqualTo("tx-stranded");
    }

    @Test
    void aRecordWithoutAnIdentityCannotBeReclaimed() {
        String key = "premigration-" + java.util.UUID.randomUUID();
        Instant claimedAt = Instant.now().minus(Duration.ofMinutes(5));
        // A record written before ADR-0014: no txId at all. attribute_exists(txId) refuses it, so no
        // resume can invent an identity for money that may already have moved.
        dynamo.putItem(r -> r.tableName("pix_idempotency").item(Map.of(
                "pk", AttributeValue.fromS(pk(ACCOUNT, key)),
                "sk", AttributeValue.fromS("META"),
                "requestHash", AttributeValue.fromS(HASH),
                "status", AttributeValue.fromS(IdempotencyStatus.CLAIMED.name()),
                "claimedAt", AttributeValue.fromS(claimedAt.toString()),
                "expiresAt", AttributeValue.fromN(
                        Long.toString(Instant.now().plus(Duration.ofHours(24)).getEpochSecond())))));

        assertThat(repository.reclaim(ACCOUNT, key, HASH, claimedAt, Instant.now())).isFalse();
        assertThat(repository.get(ACCOUNT, key).orElseThrow().hasIdentity()).isFalse();
    }

    // --- helpers -------------------------------------------------------------------------------

    private void plant(
            String key, IdempotencyStatus status, String txId, Instant claimedAt, Instant expiresAt) {
        dynamo.putItem(r -> r.tableName("pix_idempotency").item(Map.of(
                "pk", AttributeValue.fromS(pk(ACCOUNT, key)),
                "sk", AttributeValue.fromS("META"),
                "requestHash", AttributeValue.fromS(HASH),
                "txId", AttributeValue.fromS(txId),
                "endToEndId", AttributeValue.fromS("E12345678202607021234PLANTED000"),
                "status", AttributeValue.fromS(status.name()),
                "claimedAt", AttributeValue.fromS(claimedAt.toString()),
                "expiresAt", AttributeValue.fromN(Long.toString(expiresAt.getEpochSecond())))));
    }

    private Map<String, AttributeValue> item(String accountId, String key) {
        return dynamo.getItem(r -> r.tableName("pix_idempotency")
                .consistentRead(true)
                .key(Map.of(
                        "pk", AttributeValue.fromS(pk(accountId, key)),
                        "sk", AttributeValue.fromS("META")))).item();
    }

    private static String pk(String accountId, String key) {
        return "IDEM#" + accountId + "#" + key;
    }
}
