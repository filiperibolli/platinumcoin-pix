package com.platinumcoin.pix.payment.api;

import com.platinumcoin.pix.payment.domain.model.FraudDecision;
import com.platinumcoin.pix.payment.domain.model.Transaction;
import com.platinumcoin.pix.payment.domain.model.TransactionStatus;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The edge mapping from the internal state machine to the external wire vocabulary (step 22). The
 * external enum ({@code PROCESSING/SETTLED/FAILED/REVERSED/REJECTED}) deliberately hides internal
 * states so the machine can evolve without breaking clients; this test pins every transition the
 * current enum can produce, plus the money/field rendering.
 */
class PaymentResponseTest {

    private static final Instant CREATED = Instant.parse("2026-08-10T12:00:00Z");
    private static final Instant SETTLED = Instant.parse("2026-08-10T12:00:01Z");

    @Test
    void receivedMapsToProcessingWithNoSettlement() {
        Transaction received = new Transaction(
                "tx-1", "E2E-1", "acc-alice", "bob@platinum.com", null, true, 12_550L,
                TransactionStatus.RECEIVED, "lunch", null, false, CREATED, null);

        PaymentResponse response = PaymentResponse.from(received);

        assertThat(response.status()).isEqualTo("PROCESSING");
        assertThat(response.settledAt()).isNull();
        assertThat(response.failureReason()).isNull();
    }

    @Test
    void settledMapsToSettledWithSettledAt() {
        Transaction settled = new Transaction(
                "tx-1", "E2E-1", "acc-alice", "bob@platinum.com", "acc-bob", true, 12_550L,
                TransactionStatus.SETTLED, "lunch", FraudDecision.APPROVE, false, CREATED, SETTLED);

        PaymentResponse response = PaymentResponse.from(settled);

        assertThat(response.status()).isEqualTo("SETTLED");
        assertThat(response.settledAt()).isEqualTo(SETTLED);
        assertThat(response.failureReason()).isNull();
    }

    @Test
    void debitedMapsToProcessingBecauseTheMoneyHasNotReachedTheOtherBankYet() {
        // The external send (step 27): debited to clearing, awaiting settlement. The client sees the
        // same PROCESSING it saw at 202 — the internal DEBITED/SENT_TO_SPI distinction is ours, not its.
        Transaction debited = new Transaction(
                "tx-1", "E2E-1", "acc-alice", "bob@otherbank.com", null, false, 20_000L,
                TransactionStatus.DEBITED, "rent", FraudDecision.APPROVE, false, CREATED, null);

        PaymentResponse response = PaymentResponse.from(debited);

        assertThat(response.status()).isEqualTo("PROCESSING");
        assertThat(response.settledAt()).isNull();
        assertThat(response.failureReason()).isNull();
    }

    @Test
    void rendersIdentityAmountAndKeyFromTheTransaction() {
        Transaction settled = new Transaction(
                "tx-1", "E2E-1", "acc-alice", "bob@platinum.com", "acc-bob", true, 12_550L,
                TransactionStatus.SETTLED, "lunch", FraudDecision.APPROVE, false, CREATED, SETTLED);

        PaymentResponse response = PaymentResponse.from(settled);

        assertThat(response.transactionId()).isEqualTo("tx-1");
        assertThat(response.endToEndId()).isEqualTo("E2E-1");
        assertThat(response.pixKey()).isEqualTo("bob@platinum.com");
        assertThat(response.createdAt()).isEqualTo(CREATED);
        // Money is integer cents internally; formatted to a fixed 2-decimal string only at this edge.
        assertThat(response.amount()).isEqualTo("125.50");
    }
}
