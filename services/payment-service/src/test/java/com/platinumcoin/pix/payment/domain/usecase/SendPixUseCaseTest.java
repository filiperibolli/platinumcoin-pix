package com.platinumcoin.pix.payment.domain.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.platinumcoin.pix.payment.domain.EndToEndIdGenerator;
import com.platinumcoin.pix.payment.domain.InvalidAmountException;
import com.platinumcoin.pix.payment.domain.Transaction;
import com.platinumcoin.pix.payment.domain.TransactionStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * Plain-Java unit tests for the acceptance logic, with a fake port and a pinned clock — no Spring, no
 * DynamoDB. Pins what the use case is responsible for under ADR-0011: parsing money, minting ids,
 * stamping the injected clock, and taking the debtor <b>from its input</b> (the JWT), never inventing
 * it from the payload.
 */
class SendPixUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-07-02T12:34:56Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final EndToEndIdGenerator endToEndIds = new EndToEndIdGenerator("12345678");
    private final FakeTransactionRepository transactions = new FakeTransactionRepository();
    private final SendPixUseCase useCase = new SendPixUseCase(transactions, endToEndIds, clock);

    @Test
    void acceptsAValidSendAndPersistsItAsReceived() {
        Transaction result = useCase.execute(
                new SendPixCommand("acc-001", "bob@platinum.com", "125.50", "lunch"));

        Transaction persisted = transactions.only();
        assertThat(result).isSameAs(persisted);
        assertThat(persisted.debtorAccountId()).isEqualTo("acc-001");
        assertThat(persisted.creditorKey()).isEqualTo("bob@platinum.com");
        assertThat(persisted.amountCents()).isEqualTo(12550L);
        assertThat(persisted.status()).isEqualTo(TransactionStatus.RECEIVED);
        assertThat(persisted.description()).isEqualTo("lunch");
        assertThat(persisted.createdAt()).isEqualTo(NOW);
    }

    @Test
    void mintsATxIdAndAStandardEndToEndIdStampedWithTheInjectedClock() {
        Transaction result = useCase.execute(
                new SendPixCommand("acc-001", "bob@platinum.com", "10.00", null));

        assertThat(result.txId()).matches("^tx-[0-9a-fA-F-]{36}$");
        assertThat(result.endToEndId()).matches("^E12345678\\d{12}[A-Za-z0-9]{11}$");
        // The endToEndId's minute must come from the injected clock, not the wall clock.
        assertThat(result.endToEndId()).contains("202607021234");
    }

    @Test
    void defaultsAMissingDescriptionToEmptyRatherThanNull() {
        Transaction result = useCase.execute(
                new SendPixCommand("acc-001", "bob@platinum.com", "10.00", null));

        assertThat(result.description()).isEmpty();
    }

    @Test
    void refusesAZeroAmountAndPersistsNothing() {
        assertThatThrownBy(() -> useCase.execute(
                new SendPixCommand("acc-001", "bob@platinum.com", "0.00", "free?")))
                .isInstanceOf(InvalidAmountException.class);

        assertThat(transactions.created()).isEmpty();
    }

    @Test
    void debtorComesFromTheCommandNotFromAnythingInThePayload() {
        // The command's debtorAccountId is the JWT account; the pixKey names the *creditor*, and must
        // never be mistaken for the debtor. Two different accounts prove they do not cross.
        Transaction result = useCase.execute(
                new SendPixCommand("acc-001", "carol@platinum.com", "5.00", null));

        assertThat(result.debtorAccountId()).isEqualTo("acc-001");
        assertThat(result.creditorKey()).isEqualTo("carol@platinum.com");
    }
}
