package com.platinumcoin.pix.payment.infra;

import com.platinumcoin.pix.payment.domain.DailyLimitReservation;
import com.platinumcoin.pix.payment.domain.LimitDecision;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

/**
 * The only place AWS SDK types touch the daily-limit counter (ADR-0010). Implements
 * {@link DailyLimitReservation} against the {@code LIMIT#<accountId>} / {@code DAY#<yyyy-MM-dd>} item
 * of {@code pix_transactions} (docs/data-model.md §4) — a maintained counter, deliberately not a
 * query-and-sum (the table has no index by debtor account).
 *
 * <h2>Reserve is one conditional {@code UpdateItem ADD}</h2>
 * {@code ADD usedCents :amount} with condition
 * {@code attribute_not_exists(usedCents) OR usedCents <= :limitMinusAmount}. The {@code ADD} action
 * <b>upserts</b>: the first send of a day creates the item at {@code :amount}; later sends increment
 * it. Because a condition expression cannot do arithmetic, {@code :limitMinusAmount} (=
 * {@code dailyLimitCents - amountCents}) is computed here in Java, and the condition then reads "the
 * new total stays within the limit". Success ⇒ {@link LimitDecision#ALLOW}; a
 * {@code ConditionalCheckFailedException} ⇒ {@link LimitDecision#DENY} with <b>no</b> increment (a
 * cancelled conditional write changes nothing).
 *
 * <p><b>The first-send-over-limit guard.</b> On the first send of the day the item does not exist, so
 * {@code attribute_not_exists(usedCents)} is true and the {@code ADD} would proceed <i>regardless of
 * the amount</i> — the condition alone would wrongly wave through a single send larger than the whole
 * daily limit. So an amount that alone exceeds the limit ({@code limitMinusAmount < 0}) is denied here
 * before the counter is touched (docs/data-model.md §4).
 *
 * <p><b>Release is unconditional</b> {@code ADD usedCents -:amount}: a later rejection or reversal
 * (steps 21/25/33) returns exactly what it reserved. {@code ADD} on an absent item would create it at
 * a negative value, but release only ever undoes a prior reserve on the same item, so the value stays
 * non-negative in practice.
 *
 * <p>Every reserve/release also stamps {@code expiresAt} (~48h), the TTL attribute enabled on
 * {@code pix_transactions} — only {@code LIMIT#} items carry it, so DynamoDB reaps past days while
 * transaction/outbox items (which have no {@code expiresAt}) are untouched.
 */
@Repository
public class DynamoDailyLimitReservation implements DailyLimitReservation {

    private static final Logger log = LoggerFactory.getLogger(DynamoDailyLimitReservation.class);

    private static final String TABLE = "pix_transactions";
    /** ~48h so a day's counter outlives its day for late reversals, then TTL reaps it. */
    private static final Duration TTL = Duration.ofHours(48);

    private final DynamoDbClient dynamo;

    public DynamoDailyLimitReservation(DynamoDbClient dynamo) {
        this.dynamo = dynamo;
    }

    @Override
    public LimitDecision reserve(String accountId, long amountCents, long dailyLimitCents, LocalDate day) {
        long limitMinusAmount = dailyLimitCents - amountCents;
        if (limitMinusAmount < 0) {
            log.warn("Daily-limit reservation denied, the amount alone exceeds the whole limit | "
                            + "accountId={} amountCents={} dailyLimitCents={} day={}",
                    accountId, amountCents, dailyLimitCents, day);
            return LimitDecision.DENY;
        }

        String pk = pk(accountId);
        String sk = sk(day);
        log.debug("DynamoDB conditional UpdateItem to reserve daily-limit headroom | table={} pk={} "
                        + "sk={} amountCents={} limitMinusAmount={}",
                TABLE, pk, sk, amountCents, limitMinusAmount);
        try {
            dynamo.updateItem(request -> request
                    .tableName(TABLE)
                    .key(keyOf(pk, sk))
                    .updateExpression("ADD usedCents :amount SET expiresAt = :exp")
                    .conditionExpression("attribute_not_exists(usedCents) OR usedCents <= :limitMinusAmount")
                    .expressionAttributeValues(Map.of(
                            ":amount", AttributeValue.fromN(Long.toString(amountCents)),
                            ":limitMinusAmount", AttributeValue.fromN(Long.toString(limitMinusAmount)),
                            ":exp", AttributeValue.fromN(Long.toString(expiryEpoch())))));
            log.debug("Daily-limit headroom reserved | pk={} sk={} amountCents={}", pk, sk, amountCents);
            return LimitDecision.ALLOW;
        } catch (ConditionalCheckFailedException e) {
            log.warn("Daily-limit reservation denied, today's usage plus this amount would breach the "
                            + "limit | accountId={} amountCents={} dailyLimitCents={} day={}",
                    accountId, amountCents, dailyLimitCents, day);
            return LimitDecision.DENY;
        }
    }

    @Override
    public void release(String accountId, long amountCents, LocalDate day) {
        String pk = pk(accountId);
        String sk = sk(day);
        log.debug("DynamoDB UpdateItem to release daily-limit headroom | table={} pk={} sk={} "
                + "amountCents={}", TABLE, pk, sk, amountCents);
        dynamo.updateItem(request -> request
                .tableName(TABLE)
                .key(keyOf(pk, sk))
                .updateExpression("ADD usedCents :neg SET expiresAt = :exp")
                .expressionAttributeValues(Map.of(
                        ":neg", AttributeValue.fromN(Long.toString(-amountCents)),
                        ":exp", AttributeValue.fromN(Long.toString(expiryEpoch())))));
        log.debug("Daily-limit headroom released | pk={} sk={} amountCents={}", pk, sk, amountCents);
    }

    private static String pk(String accountId) {
        return "LIMIT#" + accountId;
    }

    private static String sk(LocalDate day) {
        return "DAY#" + day; // LocalDate.toString() is ISO yyyy-MM-dd
    }

    private static Map<String, AttributeValue> keyOf(String pk, String sk) {
        return Map.of("pk", AttributeValue.fromS(pk), "sk", AttributeValue.fromS(sk));
    }

    private static long expiryEpoch() {
        return Instant.now().plus(TTL).getEpochSecond();
    }
}
