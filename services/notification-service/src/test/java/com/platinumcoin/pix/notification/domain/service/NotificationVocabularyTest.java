package com.platinumcoin.pix.notification.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.platinumcoin.pix.notification.domain.usecase.DeliverNotificationCommand;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * <b>The payload contract test of step 39.</b> One question, asked three ways: <i>what does the
 * customer see, and is it a word this platform already uses?</i>
 *
 * <p>The point of standardizing here is that a client parses <b>one</b> shape. Today it would be an app
 * reading both {@code GET /payments/{transactionId}} and this stream; the day those two disagree about
 * what a finished payment is called, the client grows a translation table and the API has lost its
 * vocabulary. So the assertions below are not "the mapping is implemented" but "the mapping only ever
 * emits words {@code PaymentResponse} also emits".
 */
class NotificationVocabularyTest {

    /**
     * The complete external status vocabulary of the platform, copied from
     * {@code payment-service}'s {@code PaymentResponse} (step 22). Nothing this service pushes may fall
     * outside it — that is the whole contract, and it is asserted rather than commented.
     */
    private static final String[] EXTERNAL_VOCABULARY =
            {"PROCESSING", "SETTLED", "FAILED", "REVERSED", "REJECTED"};

    private static final Instant SETTLED_AT = Instant.parse("2026-08-20T10:15:00Z");
    private static final Instant RECEIVED_AT = Instant.parse("2026-08-20T10:16:00Z");
    private static final Instant OCCURRED_AT = Instant.parse("2026-08-20T10:17:00Z");

    @ParameterizedTest
    @ValueSource(strings = {"PixSettled", "PixReceived", "PixReversed"})
    void everyStatusThisServicePushesIsAWordTheStatusEndpointAlsoUses(String eventType) {
        var notification = NotificationVocabulary.describe(command(eventType), "acc-001");

        assertThat(notification.status())
                .as("a push must never teach a client a word GET /payments/{id} cannot answer")
                .isIn((Object[]) EXTERNAL_VOCABULARY);
    }

    @Test
    void aSettledSendIsSettled() {
        var notification = NotificationVocabulary.describe(command("PixSettled"), "acc-001");

        assertThat(notification.type()).isEqualTo("PixSettled");
        assertThat(notification.status()).isEqualTo("SETTLED");
        assertThat(notification.accountId()).isEqualTo("acc-001");
        assertThat(notification.transactionId()).isEqualTo("tx-1");
        assertThat(notification.failureReason()).isNull();
    }

    /**
     * A Pix that <i>arrived</i> is {@code SETTLED} too, and deliberately not a sixth word like
     * {@code RECEIVED}: the money is here and it is final, which is exactly what {@code SETTLED} means
     * everywhere else in this platform. The direction is already carried by {@code type} — inventing a
     * status for it would put the same fact in two fields and give a client two ways to disagree with
     * itself.
     */
    @Test
    void anArrivedPixIsSettledToo() {
        var notification = NotificationVocabulary.describe(command("PixReceived"), "acc-002");

        assertThat(notification.type()).isEqualTo("PixReceived");
        assertThat(notification.status()).isEqualTo("SETTLED");
    }

    @Test
    void aReversalCarriesTheReasonItWasReversed() {
        // REVERSED already exists in the status vocabulary (step 33), so the failure branch of the funnel
        // needs no new word either — only the reason, which is the one thing a customer will ask about.
        var notification = NotificationVocabulary.describe(command("PixReversed"), "acc-001");

        assertThat(notification.status()).isEqualTo("REVERSED");
        assertThat(notification.failureReason()).isEqualTo("CREDITOR_KEY_NOT_IN_DICT");
    }

    /**
     * Whose name goes on the screen. An outbound outcome shows where the money was going; an arrival
     * shows who sent it — and neither is ever an internal account id, which would be both meaningless
     * to a human and a small leak of our own identifiers.
     */
    @Test
    void theCounterpartIsThePayeeOnASendAndThePayerOnAnArrival() {
        assertThat(NotificationVocabulary.describe(command("PixSettled"), "acc-001").counterpart())
                .isEqualTo("bob@otherbank.com");
        assertThat(NotificationVocabulary.describe(command("PixReversed"), "acc-001").counterpart())
                .isEqualTo("bob@otherbank.com");
        assertThat(NotificationVocabulary.describe(command("PixReceived"), "acc-002").counterpart())
                .isEqualTo("Carol");
    }

    @Test
    void anArrivalWithoutAPayerNameFallsBackToTheParticipantAndThenToNothing() {
        // payerName is descriptive and optional on the wire (SettlementOutboxEvents#pixReceived writes it
        // only when BACEN sent one), so the display has to degrade rather than print "null".
        var namelessCommand = base("PixReceived").payerName(null).build();
        assertThat(NotificationVocabulary.describe(namelessCommand, "acc-002").counterpart())
                .isEqualTo("99999999");

        var anonymousCommand = base("PixReceived").payerName(null).payerIspb(null).build();
        assertThat(NotificationVocabulary.describe(anonymousCommand, "acc-002").counterpart()).isNull();
    }

    /**
     * The instant a customer is shown is the instant the <i>money</i> reached its outcome, not the one
     * our outbox happened to be written at. They differ by milliseconds today and by minutes the day a
     * publisher backs up — and a receipt showing the second one would be wrong exactly when it matters.
     */
    @Test
    void theTimestampIsWhenTheMoneyMovedNotWhenWeAnnouncedIt() {
        assertThat(NotificationVocabulary.describe(command("PixSettled"), "acc-001").timestamp())
                .isEqualTo(SETTLED_AT);
        assertThat(NotificationVocabulary.describe(command("PixReceived"), "acc-002").timestamp())
                .isEqualTo(RECEIVED_AT);
        // A reversal has no instant of its own in the payload: occurredAt IS when it happened (step 33
        // writes the compensating posting and the event in one transaction).
        assertThat(NotificationVocabulary.describe(command("PixReversed"), "acc-001").timestamp())
                .isEqualTo(OCCURRED_AT);
    }

    @Test
    void aSettlementWithoutItsOwnInstantFallsBackToWhenWeRecordedIt() {
        var command = base("PixSettled").settledAt(null).build();

        assertThat(NotificationVocabulary.describe(command, "acc-001").timestamp())
                .isEqualTo(OCCURRED_AT);
    }

    /**
     * Money crosses this service untouched, as integer cents (Domain Safety Rule #6). R$ 1.234.567,89 is
     * past {@code Integer.MAX_VALUE} in cents on purpose: a value narrowed to an {@code int} anywhere on
     * this path fails here rather than on the one payment large enough to matter.
     */
    @Test
    void moneyIsCarriedAsExactIntegerCents() {
        var command = base("PixSettled").amountCents(123_456_789L).build();

        assertThat(NotificationVocabulary.describe(command, "acc-001").amountCents())
                .isEqualTo(123_456_789L);
    }

    /**
     * Unreachable in production — {@code NotificationRouting} answers "no addressee" for an unknown type
     * and the use case drops the event before ever asking for its wording. Pinned anyway so that a
     * fourth event type added upstream fails loudly here instead of arriving on a customer's screen as
     * an empty status.
     */
    @Test
    void anEventTypeWithNoCustomerFacingWordingIsRefused() {
        assertThatThrownBy(() -> NotificationVocabulary.describe(command("PixDebited"), "acc-001"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PixDebited");
    }

    private static DeliverNotificationCommand command(String eventType) {
        return base(eventType).build();
    }

    private static CommandBuilder base(String eventType) {
        return new CommandBuilder(eventType);
    }

    /** A readable way to vary one field of a wide command without repeating thirteen arguments. */
    private static final class CommandBuilder {

        private final String eventType;
        private long amountCents = 12_345L;
        private String payerName = "Carol";
        private String payerIspb = "99999999";
        private Instant settledAt = SETTLED_AT;

        private CommandBuilder(String eventType) {
            this.eventType = eventType;
        }

        CommandBuilder amountCents(long value) {
            this.amountCents = value;
            return this;
        }

        CommandBuilder payerName(String value) {
            this.payerName = value;
            return this;
        }

        CommandBuilder payerIspb(String value) {
            this.payerIspb = value;
            return this;
        }

        CommandBuilder settledAt(Instant value) {
            this.settledAt = value;
            return this;
        }

        DeliverNotificationCommand build() {
            return new DeliverNotificationCommand(
                    "evt-1", eventType, "cid-1", "tx-1",
                    "acc-001", "acc-002", amountCents,
                    "bob@otherbank.com", payerName, payerIspb,
                    "CREDITOR_KEY_NOT_IN_DICT",
                    settledAt, RECEIVED_AT, OCCURRED_AT);
        }
    }
}
