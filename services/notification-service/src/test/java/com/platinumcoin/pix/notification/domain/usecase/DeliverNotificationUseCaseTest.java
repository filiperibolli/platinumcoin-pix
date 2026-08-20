package com.platinumcoin.pix.notification.domain.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.platinumcoin.pix.notification.domain.usecase.DeliverOutcome.Kind;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The delivery capability in plain Java: dedupe, route, push — and, above all, <b>never push one
 * account's payment to another account</b>. Every assertion here is about who receives what and what
 * the consumer is then allowed to do with the message.
 */
class DeliverNotificationUseCaseTest {

    private static final Instant SETTLED_AT = Instant.parse("2026-08-20T10:15:00Z");

    private FakeProcessedEvents processedEvents;
    private FakeNotificationChannel channel;
    private DeliverNotificationUseCase useCase;

    @BeforeEach
    void setUp() {
        processedEvents = new FakeProcessedEvents();
        channel = new FakeNotificationChannel();
        useCase = new DeliverNotificationUseCase(processedEvents, channel);
    }

    @Test
    void pushesAnInboundPixToThePayeeOnly() {
        var outcome = useCase.execute(received("evt-1", "acc-002", 12_345L));

        assertThat(outcome.kind()).isEqualTo(Kind.DELIVERED);
        assertThat(outcome.accountId()).isEqualTo("acc-002");
        assertThat(channel.pushes).singleElement()
                .satisfies(push -> assertThat(push.accountId()).isEqualTo("acc-002"));
    }

    @Test
    void pushesAnOutboundOutcomeToThePayerOnly() {
        var outcome = useCase.execute(settled("evt-1", "acc-001", 500L));

        assertThat(outcome.accountId()).isEqualTo("acc-001");
        assertThat(channel.pushes).singleElement()
                .satisfies(push -> assertThat(push.accountId()).isEqualTo("acc-001"));
    }

    @Test
    void moneyReachesTheStreamAsIntegerCentsUnchanged() {
        // The money invariant on this path: notification-service moves no money, but it must not
        // MISREPORT any either. R$ 1.234.567,89 has to arrive as the exact long it left the ledger as —
        // a value that loses its last cents to a float somewhere is a support ticket, and the same slip
        // in a service that DOES post would be a real loss. No decimal conversion happens here at all;
        // formatting is the API edge's job (step 39).
        long amountCents = 123_456_789L;

        useCase.execute(received("evt-1", "acc-002", amountCents));

        assertThat(channel.pushes).singleElement()
                .satisfies(push -> assertThat(push.notification().amountCents()).isEqualTo(amountCents));
    }

    @Test
    void aRedeliveredEventIsPushedExactlyOnce() {
        // At-least-once delivery is the broker's contract (ADR-0004), so the SAME eventId will arrive
        // again. Pushing twice would show the customer two payments where one arrived.
        useCase.execute(received("evt-1", "acc-002", 900L));
        var second = useCase.execute(received("evt-1", "acc-002", 900L));

        assertThat(second.kind()).isEqualTo(Kind.DUPLICATE);
        assertThat(channel.pushes).hasSize(1);
    }

    @Test
    void twoDifferentEventsAboutTheSamePaymentBothPush() {
        // Dedup is per eventId, never per txId: a settle and a later reversal of one payment are two
        // facts the customer must see, not a duplicate of each other.
        useCase.execute(settled("evt-1", "acc-001", 900L));
        useCase.execute(reversed("evt-2", "acc-001", 900L));

        assertThat(channel.pushes).hasSize(2);
    }

    @Test
    void anEventForAnAccountWithNoOpenStreamIsDroppedAndAcked() {
        // Best-effort by design (step 38): nobody is listening, and the state stays queryable on
        // GET /payments/{id}. Holding the message for a customer who may not open the app for a week
        // would fill the queue and eventually the DLQ with work that can never succeed.
        channel.subscribersPerAccount = 0;

        var outcome = useCase.execute(received("evt-1", "acc-002", 900L));

        assertThat(outcome.kind()).isEqualTo(Kind.NO_SUBSCRIBER);
        assertThat(outcome.subscribersReached()).isZero();
    }

    @Test
    void anUnroutableEventIsAckedWithoutPushing() {
        var outcome = useCase.execute(command("evt-1", "PixDebited", "acc-001", null, 900L));

        assertThat(outcome.kind()).isEqualTo(Kind.UNROUTABLE);
        assertThat(channel.pushes).isEmpty();
    }

    /**
     * Routing runs <b>before</b> the wording, and that order is load-bearing: {@code PixDebited} has no
     * customer-facing status, so describing it would throw — but nobody can be named for it either, and
     * the event is dropped first. Asserting the outcome above is only half the story; this pins that the
     * unknown type never reaches {@link com.platinumcoin.pix.notification.domain.service
     * .NotificationVocabulary} at all, which is why an upstream change surfaces as an acked
     * {@code UNROUTABLE} in the logs rather than as a message looping into the DLQ.
     */
    @Test
    void anEventWithNoCustomerFacingWordingIsDroppedBeforeItCanBeDescribed() {
        var outcome = useCase.execute(command("evt-1", "FraudCheckSkipped", "acc-001", null, 900L));

        assertThat(outcome.kind()).isEqualTo(Kind.UNROUTABLE);
        assertThat(channel.pushes).isEmpty();
    }

    /**
     * What the customer is actually told — the step-39 shape, composed here and pushed as one piece:
     * the external status vocabulary, the counterpart, and cents still cents (the decimal string is the
     * transport's job, one layer further out).
     */
    @Test
    void thePushCarriesTheCustomerFacingWordingNotTheRawEvent() {
        useCase.execute(settled("evt-1", "acc-001", 12_550L));

        assertThat(channel.pushes).singleElement().satisfies(push -> {
            assertThat(push.notification().type()).isEqualTo("PixSettled");
            assertThat(push.notification().status()).isEqualTo("SETTLED");
            assertThat(push.notification().counterpart()).isEqualTo("bob@otherbank.com");
            assertThat(push.notification().transactionId()).isEqualTo("tx-1");
            assertThat(push.notification().amountCents()).isEqualTo(12_550L);
            assertThat(push.notification().failureReason())
                    .as("a settled payment never carries a failure reason")
                    .isNull();
        });
    }

    /** The failure branch of the funnel: the payer is told the money came back, and why. */
    @Test
    void aReversalTellsThePayerWhyTheMoneyCameBack() {
        useCase.execute(reversed("evt-1", "acc-001", 12_550L));

        assertThat(channel.pushes).singleElement().satisfies(push -> {
            assertThat(push.notification().status()).isEqualTo("REVERSED");
            assertThat(push.notification().failureReason()).isEqualTo("CREDITOR_KEY_NOT_IN_DICT");
        });
    }

    @Test
    void aBrokenTransportReleasesTheClaimSoTheRedeliveryIsRealWork() {
        // The claim means "I am handling this" (ProcessedEventStore). If the push blew up, we are NOT
        // handling it — keeping the claim would have the redelivery deduped away and the customer would
        // never be told, which is the one way a best-effort consumer still loses information silently.
        channel.failure = new IllegalStateException("emitter registry exploded");

        assertThatThrownBy(() -> useCase.execute(received("evt-1", "acc-002", 900L)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(processedEvents.released).containsExactly("evt-1");
        assertThat(processedEvents.claimed).isEmpty();
    }

    private static DeliverNotificationCommand received(String eventId, String creditor, long amountCents) {
        return command(eventId, "PixReceived", null, creditor, amountCents);
    }

    private static DeliverNotificationCommand settled(String eventId, String debtor, long amountCents) {
        return command(eventId, "PixSettled", debtor, null, amountCents);
    }

    private static DeliverNotificationCommand reversed(String eventId, String debtor, long amountCents) {
        return command(eventId, "PixReversed", debtor, null, amountCents);
    }

    private static DeliverNotificationCommand command(String eventId, String eventType, String debtor,
            String creditor, long amountCents) {
        return new DeliverNotificationCommand(eventId, eventType, "cid-1", "tx-1",
                debtor, creditor, amountCents, "bob@otherbank.com", "Carol", "99999999",
                "CREDITOR_KEY_NOT_IN_DICT", SETTLED_AT, SETTLED_AT, SETTLED_AT);
    }
}
