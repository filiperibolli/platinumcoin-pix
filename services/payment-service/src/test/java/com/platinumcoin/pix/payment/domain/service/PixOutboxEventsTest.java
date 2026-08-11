package com.platinumcoin.pix.payment.domain.service;

import com.platinumcoin.pix.common.event.OutboxEvent;
import com.platinumcoin.pix.payment.domain.model.FraudDecision;
import com.platinumcoin.pix.payment.domain.model.Transaction;
import com.platinumcoin.pix.payment.domain.model.TransactionStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a freshly accepted send announces to the rest of the platform (step 28). The event list is the
 * contract every downstream service reads, so it is pinned here rather than only observed through
 * DynamoDB — and the <b>choice</b> of event type is a business decision, which is why it lives in
 * {@code domain/} and not in the repository adapter.
 */
class PixOutboxEventsTest {

    private static final Instant NOW = Instant.parse("2026-07-02T12:34:56.789Z");

    private static Transaction external(FraudDecision fraud) {
        return new Transaction("tx-1", "E1234", "acc-001", "bob@otherbank.com", null, false,
                12_550L, TransactionStatus.DEBITED, "rent", fraud,
                fraud == FraudDecision.SKIPPED, NOW, null);
    }

    private static Transaction internal(FraudDecision fraud) {
        return new Transaction("tx-2", "E5678", "acc-001", "bob@platinum.com", "acc-002", true,
                12_550L, TransactionStatus.SETTLED, "lunch", fraud,
                fraud == FraudDecision.SKIPPED, NOW, NOW);
    }

    private static List<String> typesOf(List<OutboxEvent> events) {
        return events.stream().map(OutboxEvent::eventType).toList();
    }

    /**
     * An external send announces {@code PixDebited} — the trigger the settlement-queue's filter policy
     * subscribes to (step 26). The money is in clearing and BACEN has not been called yet.
     */
    @Test
    void anExternalSendAnnouncesPixDebited() {
        List<OutboxEvent> events = PixOutboxEvents.forAcceptedSend(external(FraudDecision.APPROVE), NOW);

        assertThat(typesOf(events)).containsExactly("PixDebited");
        OutboxEvent event = events.get(0);
        assertThat(event.eventId()).startsWith("evt-");
        assertThat(event.occurredAt()).isEqualTo(NOW);
        assertThat(event.payload())
                .containsEntry("txId", "tx-1")
                .containsEntry("endToEndId", "E1234")
                .containsEntry("debtorAccountId", "acc-001")
                .containsEntry("creditorKey", "bob@otherbank.com")
                .containsEntry("amountCents", 12_550L)
                .containsEntry("status", "DEBITED")
                .containsEntry("description", "rent")
                .containsEntry("occurredAt", "2026-07-02T12:34:56.789Z");
        // The payee banks elsewhere: there is no internal account to name.
        assertThat(event.payload()).doesNotContainKey("creditorAccountId");
        assertThat(event.payload()).doesNotContainKey("settledAt");
    }

    /**
     * An internal send announces {@code PixSettled}, never {@code PixDebited}: the money already reached
     * the payee in one atomic posting (step 21). Emitting {@code PixDebited} would put it on the
     * settlement-queue and have settlement-service ask BACEN to settle a Pix that never leaves the bank.
     */
    @Test
    void anInternalSendAnnouncesPixSettledAndNeverPixDebited() {
        List<OutboxEvent> events = PixOutboxEvents.forAcceptedSend(internal(FraudDecision.APPROVE), NOW);

        assertThat(typesOf(events)).containsExactly("PixSettled");
        assertThat(events.get(0).payload())
                .containsEntry("txId", "tx-2")
                .containsEntry("creditorAccountId", "acc-002")
                .containsEntry("status", "SETTLED")
                .containsEntry("settledAt", "2026-07-02T12:34:56.789Z")
                .containsEntry("amountCents", 12_550L);
    }

    /**
     * A fail-open skip (ADR-0005) is not forgotten: it rides out as a second event in the <b>same</b>
     * atomic write, so "we let an unscored payment through" is as durable as the payment itself.
     */
    @Test
    void aSkippedFraudCheckAddsASecondEventToTheSameWrite() {
        List<OutboxEvent> events = PixOutboxEvents.forAcceptedSend(external(FraudDecision.SKIPPED), NOW);

        assertThat(typesOf(events)).containsExactly("PixDebited", "FraudCheckSkipped");
        assertThat(events.get(1).payload())
                .containsEntry("txId", "tx-1")
                .containsEntry("debtorAccountId", "acc-001")
                .containsEntry("creditorKey", "bob@otherbank.com")
                .containsEntry("amountCents", 12_550L);
        // Two events, two distinct de-duplication keys — consumers dedupe by eventId (ADR-0004).
        assertThat(events.get(0).eventId()).isNotEqualTo(events.get(1).eventId());
    }

    @Test
    void aSkippedFraudCheckOnAnInternalSendAlsoRidesAlong() {
        assertThat(typesOf(PixOutboxEvents.forAcceptedSend(internal(FraudDecision.SKIPPED), NOW)))
                .containsExactly("PixSettled", "FraudCheckSkipped");
    }

    /** Every event of one send is minted with its own id — an eventId is never a transaction id. */
    @Test
    void mintsAFreshEventIdPerEvent() {
        String first = PixOutboxEvents.forAcceptedSend(external(FraudDecision.APPROVE), NOW)
                .get(0).eventId();
        String second = PixOutboxEvents.forAcceptedSend(external(FraudDecision.APPROVE), NOW)
                .get(0).eventId();

        assertThat(first).isNotEqualTo(second);
    }

    /** Money is integer cents in the payload too — never a decimal string, never a double. */
    @Test
    void carriesMoneyAsIntegerCents() {
        Object amount = PixOutboxEvents.forAcceptedSend(external(FraudDecision.APPROVE), NOW)
                .get(0).payload().get("amountCents");

        assertThat(amount).isInstanceOf(Long.class).isEqualTo(12_550L);
    }
}
