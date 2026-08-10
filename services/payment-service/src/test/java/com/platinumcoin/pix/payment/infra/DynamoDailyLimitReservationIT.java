package com.platinumcoin.pix.payment.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.platinumcoin.pix.common.testsupport.LocalStackTestBase;
import com.platinumcoin.pix.payment.domain.DailyLimitReservation;
import com.platinumcoin.pix.payment.domain.LimitDecision;
import com.platinumcoin.pix.payment.support.PaymentTestSupport;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * The reserve/release counter over the real {@code pix_transactions} {@code LIMIT#}/{@code DAY#} item
 * (docs/data-model.md §4), on LocalStack. Proves the money invariants of step 20 directly on the
 * adapter: a reservation is atomic and bounded by the limit, an over-limit reserve leaves the counter
 * untouched, calendar days are independent, and a release restores exactly what it reserved. Each test
 * uses its own account so the shared singleton container needs no cleanup between tests.
 */
@SpringBootTest
@Import(PaymentTestSupport.class)
class DynamoDailyLimitReservationIT extends LocalStackTestBase {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 7);
    private static final LocalDate YESTERDAY = TODAY.minusDays(1);

    @Autowired
    DailyLimitReservation reservation;

    @Autowired
    DynamoDbClient dynamo;

    @Test
    void reserveUnderTheLimitAllowsAndRecordsExactlyTheAmount() {
        String account = "acc-limit-allow";

        LimitDecision decision = reservation.reserve(account, 12_550L, 500_000L, TODAY);

        assertThat(decision).isEqualTo(LimitDecision.ALLOW);
        assertThat(usedCents(account, TODAY)).isEqualTo(12_550L);
    }

    @Test
    void reserveThatWouldCrossTheLimitIsDeniedAndLeavesTheCounterUnchanged() {
        String account = "acc-limit-cross";
        long limit = 20_000L; // R$ 200,00

        assertThat(reservation.reserve(account, 15_000L, limit, TODAY)).isEqualTo(LimitDecision.ALLOW);
        // 15_000 already used; a further 10_000 would make 25_000 > 20_000 → denied, no increment.
        assertThat(reservation.reserve(account, 10_000L, limit, TODAY)).isEqualTo(LimitDecision.DENY);

        assertThat(usedCents(account, TODAY)).isEqualTo(15_000L);
    }

    @Test
    void aSingleAmountLargerThanTheWholeLimitIsDeniedWithoutCreatingTheCounter() {
        String account = "acc-limit-single-over";

        // First send of the day, but it alone exceeds the limit: attribute_not_exists must NOT wave it
        // through — the limitMinusAmount<0 guard denies before the counter is touched.
        assertThat(reservation.reserve(account, 90_000L, 50_000L, TODAY)).isEqualTo(LimitDecision.DENY);

        assertThat(counterItem(account, TODAY)).isEmpty();
    }

    @Test
    void yesterdaysUsageDoesNotCountAgainstTodaysHeadroom() {
        String account = "acc-limit-dayboundary";
        long limit = 50_000L;

        // Fill yesterday to the brim.
        assertThat(reservation.reserve(account, 50_000L, limit, YESTERDAY)).isEqualTo(LimitDecision.ALLOW);
        // Today is a fresh window — a full-limit send is allowed again.
        assertThat(reservation.reserve(account, 50_000L, limit, TODAY)).isEqualTo(LimitDecision.ALLOW);

        assertThat(usedCents(account, YESTERDAY)).isEqualTo(50_000L);
        assertThat(usedCents(account, TODAY)).isEqualTo(50_000L);
    }

    @Test
    void aReleaseAfterARejectionRestoresTodaysHeadroomExactly() {
        String account = "acc-limit-release";
        long limit = 50_000L;

        // Use the full limit, so the next reserve is denied.
        assertThat(reservation.reserve(account, 50_000L, limit, TODAY)).isEqualTo(LimitDecision.ALLOW);
        assertThat(reservation.reserve(account, 10_000L, limit, TODAY)).isEqualTo(LimitDecision.DENY);

        // A later rejection in the flow releases what it had reserved.
        reservation.release(account, 20_000L, TODAY);
        assertThat(usedCents(account, TODAY)).isEqualTo(30_000L);

        // Exactly 20_000 of headroom is back — a 20_000 send now fits, a 20_001 would not.
        assertThat(reservation.reserve(account, 20_000L, limit, TODAY)).isEqualTo(LimitDecision.ALLOW);
        assertThat(usedCents(account, TODAY)).isEqualTo(50_000L);
    }

    private long usedCents(String account, LocalDate day) {
        return Long.parseLong(counterItem(account, day).get("usedCents").n());
    }

    private Map<String, AttributeValue> counterItem(String account, LocalDate day) {
        return dynamo.getItem(request -> request
                .tableName("pix_transactions")
                .consistentRead(true)
                .key(Map.of(
                        "pk", AttributeValue.fromS("LIMIT#" + account),
                        "sk", AttributeValue.fromS("DAY#" + day)))).item();
    }
}
