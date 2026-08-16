package com.platinumcoin.pix.settlement.infra.persistence;

import com.platinumcoin.pix.settlement.domain.port.DailyLimitRelease;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Returns daily-limit headroom to a payer after a reversal (step 33). Writes the same
 * {@code LIMIT#<accountId>/DAY#<day>} counter in {@code pix_transactions} that payment-service reserves
 * against (docs/data-model.md §4) — the table settlement already writes under ADR-0006's documented
 * exception, so no internal API is added just to decrement a counter.
 *
 * <p><b>Byte-identical to payment-service's release leg on purpose:</b> {@code ADD usedCents -:amount}
 * (unconditional — a release only ever undoes a prior reserve) plus an {@code expiresAt} refresh so the
 * counter outlives its day for a late reversal, then the {@code pix_transactions} TTL reaps it. The two
 * services must speak to this item identically, or a release from here would not net against a reserve
 * from there.
 *
 * <p><b>Not idempotent</b> ({@code ADD} run twice double-refunds), which is exactly why the use case calls
 * this only when the guarded {@code SENT_TO_SPI → REVERSED} transition wins — see {@link DailyLimitRelease}.
 */
@Repository
public class DynamoDailyLimitRelease implements DailyLimitRelease {

    private static final Logger log = LoggerFactory.getLogger(DynamoDailyLimitRelease.class);

    private static final String TABLE = "pix_transactions";
    /** ~48h so a day's counter outlives its day for a late reversal, then TTL reaps it. */
    private static final Duration TTL = Duration.ofHours(48);

    private final DynamoDbClient dynamo;

    public DynamoDailyLimitRelease(DynamoDbClient dynamo) {
        this.dynamo = dynamo;
    }

    @Override
    public void release(String accountId, long amountCents, LocalDate day) {
        String pk = "LIMIT#" + accountId;
        String sk = "DAY#" + day; // LocalDate.toString() is ISO yyyy-MM-dd, matching the reserve leg
        log.debug("DynamoDB UpdateItem to release daily-limit headroom after a reversal | table={} pk={} "
                + "sk={} amountCents={}", TABLE, pk, sk, amountCents);
        dynamo.updateItem(request -> request
                .tableName(TABLE)
                .key(Map.of("pk", AttributeValue.fromS(pk), "sk", AttributeValue.fromS(sk)))
                .updateExpression("ADD usedCents :neg SET expiresAt = :exp")
                .expressionAttributeValues(Map.of(
                        ":neg", AttributeValue.fromN(Long.toString(-amountCents)),
                        ":exp", AttributeValue.fromN(Long.toString(expiryEpoch())))));
        log.debug("Daily-limit headroom released | pk={} sk={} amountCents={}", pk, sk, amountCents);
    }

    private static long expiryEpoch() {
        return Instant.now().plus(TTL).getEpochSecond();
    }
}
