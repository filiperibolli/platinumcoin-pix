package com.platinumcoin.pix.notification.infra.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.platinumcoin.pix.notification.domain.model.Notification;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The money invariant of this service, at the only place it is allowed to change shape.
 *
 * <p>Cents stay a {@code long} from the ledger through SNS, SQS and the domain; <b>here</b> — the
 * outbound web adapter, which is this service's API edge — they become the decimal string a human
 * reads. Same discipline as {@code PaymentResponse}, same technique: {@link java.math.BigDecimal} with a
 * decimal-point shift, which is an exact base-10 move — no division, no floating point, therefore no
 * rounding mode to get wrong. A {@code double} would already be wrong at R$ 1.234.567,89.
 */
class NotificationPayloadTest {

    private static final Instant TIMESTAMP = Instant.parse("2026-08-20T10:15:00Z");

    @ParameterizedTest
    @CsvSource({
            "0,          0.00",
            "1,          0.01",
            "99,         0.99",
            "100,        1.00",
            "12550,      125.50",
            "500000,     5000.00",
            // Past Integer.MAX_VALUE in cents: the value that catches an int anywhere on the path.
            "123456789,  1234567.89",
            "999999999999, 9999999999.99",
    })
    void centsBecomeAFixedTwoDecimalStringExactly(long cents, String expected) {
        var payload = NotificationPayload.of(notification(cents));

        assertThat(payload.amount()).isEqualTo(expected);
    }

    @Test
    void theWireShapeIsTheSameOneTheStatusEndpointAnswers() {
        var payload = NotificationPayload.of(notification(12_550L));

        assertThat(payload.transactionId()).isEqualTo("tx-1");
        assertThat(payload.type()).isEqualTo("PixSettled");
        assertThat(payload.status()).isEqualTo("SETTLED");
        assertThat(payload.counterpart()).isEqualTo("bob@otherbank.com");
        assertThat(payload.timestamp()).isEqualTo(TIMESTAMP);
        assertThat(payload.failureReason()).isNull();
    }

    /**
     * The customer's own account id is <b>not</b> on the wire, and that is not an omission. The stream
     * is opened with a JWT and only ever carries that caller's events, so an account field would be a
     * value the client already knows — and the one field an attacker would look for to learn whether it
     * had been given someone else's frame.
     */
    @Test
    void thePayloadNamesNoAccount() {
        var payload = NotificationPayload.of(notification(12_550L));

        assertThat(payload.toString()).doesNotContain("acc-001");
    }

    private static Notification notification(long amountCents) {
        return new Notification("evt-1", "acc-001", "PixSettled", "SETTLED", "tx-1",
                amountCents, "bob@otherbank.com", TIMESTAMP, null);
    }
}
