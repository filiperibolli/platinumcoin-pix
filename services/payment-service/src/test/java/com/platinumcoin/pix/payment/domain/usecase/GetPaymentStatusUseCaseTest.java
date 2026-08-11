package com.platinumcoin.pix.payment.domain.usecase;

import com.platinumcoin.pix.payment.domain.exception.PaymentNotFoundException;
import com.platinumcoin.pix.payment.domain.model.FraudDecision;
import com.platinumcoin.pix.payment.domain.model.Transaction;
import com.platinumcoin.pix.payment.domain.model.TransactionStatus;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Plain-Java contract of the status query (step 22): ownership is enforced here (ADR-0011), and both
 * "unknown id" and "someone else's transaction" collapse to the same {@link PaymentNotFoundException}
 * so the endpoint never leaks the existence of another account's payment.
 */
class GetPaymentStatusUseCaseTest {

    private static final String OWNER = "acc-owner";
    private static final Instant CREATED = Instant.parse("2026-08-10T12:00:00Z");

    private final FakeTransactionRepository transactions = new FakeTransactionRepository();
    private final GetPaymentStatusUseCase useCase = new GetPaymentStatusUseCase(transactions);

    @Test
    void ownerReadsTheirOwnTransaction() {
        Transaction settled = tx("tx-1", OWNER, TransactionStatus.SETTLED, CREATED);
        transactions.save(settled);

        Transaction found = useCase.execute("tx-1", OWNER);

        assertThat(found).isEqualTo(settled);
    }

    @Test
    void anUnknownIdIsNotFound() {
        assertThatThrownBy(() -> useCase.execute("tx-missing", OWNER))
                .isInstanceOf(PaymentNotFoundException.class);
    }

    @Test
    void anotherAccountsTransactionIsNotFoundNotForbidden() {
        transactions.save(tx("tx-1", "acc-someone-else", TransactionStatus.SETTLED, CREATED));

        // A caller who is not the debtor gets 404, never 403: existence must not leak.
        assertThatThrownBy(() -> useCase.execute("tx-1", OWNER))
                .isInstanceOf(PaymentNotFoundException.class);
    }

    private static Transaction tx(String id, String debtor, TransactionStatus status, Instant createdAt) {
        Instant settledAt = status == TransactionStatus.SETTLED ? createdAt : null;
        String creditorAccountId = status == TransactionStatus.SETTLED ? "acc-bob" : null;
        FraudDecision fraudDecision = status == TransactionStatus.SETTLED ? FraudDecision.APPROVE : null;
        return new Transaction(
                id, "E2E-" + id, debtor, "bob@platinum.com", creditorAccountId, true, 12_550L, status,
                "lunch", fraudDecision, false, createdAt, settledAt);
    }
}
