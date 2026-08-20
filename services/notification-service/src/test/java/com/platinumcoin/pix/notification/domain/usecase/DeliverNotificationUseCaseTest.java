package com.platinumcoin.pix.notification.domain.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.platinumcoin.pix.notification.domain.usecase.DeliverOutcome.Kind;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The delivery capability in plain Java: dedupe, route, push — and, above all, <b>never push one
 * account's payment to another account</b>. Every assertion here is about who receives what and what
 * the consumer is then allowed to do with the message.
 */
class DeliverNotificationUseCaseTest {

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
        var outcome = useCase.execute(new DeliverNotificationCommand(
                "evt-1", "PixDebited", "cid-1", "tx-1", "acc-001", null, 900L, Map.of()));

        assertThat(outcome.kind()).isEqualTo(Kind.UNROUTABLE);
        assertThat(channel.pushes).isEmpty();
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
        return new DeliverNotificationCommand(eventId, "PixReceived", "cid-1", "tx-1",
                null, creditor, amountCents, Map.of("amountCents", amountCents));
    }

    private static DeliverNotificationCommand settled(String eventId, String debtor, long amountCents) {
        return new DeliverNotificationCommand(eventId, "PixSettled", "cid-1", "tx-1",
                debtor, null, amountCents, Map.of("amountCents", amountCents));
    }

    private static DeliverNotificationCommand reversed(String eventId, String debtor, long amountCents) {
        return new DeliverNotificationCommand(eventId, "PixReversed", "cid-1", "tx-1",
                debtor, null, amountCents, Map.of("amountCents", amountCents));
    }
}
