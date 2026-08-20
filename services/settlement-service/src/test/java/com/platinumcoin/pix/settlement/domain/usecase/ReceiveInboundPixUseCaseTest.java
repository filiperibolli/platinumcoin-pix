package com.platinumcoin.pix.settlement.domain.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.platinumcoin.pix.settlement.domain.exception.DirectoryUnavailableException;
import com.platinumcoin.pix.settlement.domain.exception.InboundKeyNotFoundException;
import com.platinumcoin.pix.settlement.domain.exception.InvalidWebhookTokenException;
import com.platinumcoin.pix.settlement.domain.exception.LedgerUnavailableException;
import com.platinumcoin.pix.settlement.domain.model.InboundTransaction;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The inbound decision, pinned in plain Java (step 37): no Spring, no HTTP, no DynamoDB — three fakes and
 * a pinned clock. What this test exists to nail down is the part an integration test can only observe
 * indirectly: <b>the order of the four steps</b>, and what each failure leaves behind.
 *
 * <p>The shared {@code trace} list is the instrument. Every fake appends its own name when it is called,
 * so an assertion on the trace is an assertion on the sequence — which is the design here, not an
 * implementation detail. "A wrong token resolved nothing and posted nothing" is a security property, and
 * "the posting happened before the record" is the money-correctness property the whole ordering argument
 * rests on.
 */
class ReceiveInboundPixUseCaseTest {

    private static final String TOKEN = "dev-only-webhook-token";
    private static final String E2E_ID = "E99999999202608201030abcdef01234";
    private static final String PAYEE_KEY = "bob@platinum.com";
    private static final String PAYEE_ACCOUNT = "acc-002";
    private static final String CLEARING = "SPI_CLEARING";
    private static final long AMOUNT = 30_000L;
    private static final Instant NOW = Instant.parse("2026-08-20T10:30:00Z");

    private final List<String> trace = new ArrayList<>();
    private FakePixKeyResolver keys;
    private FakeLedgerClient ledger;
    private FakeInboundTransactionStore transactions;
    private ReceiveInboundPixUseCase useCase;

    @BeforeEach
    void setUp() {
        trace.clear();
        keys = new FakePixKeyResolver(trace);
        ledger = new FakeLedgerClient(trace);
        transactions = new FakeInboundTransactionStore(trace);
        keys.register(PAYEE_KEY, PAYEE_ACCOUNT);
        useCase = new ReceiveInboundPixUseCase(keys, ledger, transactions, TOKEN, CLEARING,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void creditsThePayeeFromClearingAndRecordsTheTransactionWithItsPixReceived() {
        assertThat(useCase.execute(command(), TOKEN)).isEqualTo(ReceiveInboundOutcome.CREDITED);

        // The mirror of an outbound send: debit clearing, credit the payee — keyed by in-<endToEndId>.
        assertThat(ledger.inboundCredits()).singleElement().satisfies(posting -> {
            assertThat(posting.txId()).isEqualTo("in-" + E2E_ID);
            assertThat(posting.debitAccount()).isEqualTo(CLEARING);
            assertThat(posting.creditAccount()).isEqualTo(PAYEE_ACCOUNT);
            assertThat(posting.amountCents()).isEqualTo(AMOUNT);
        });

        assertThat(transactions.recorded()).singleElement().satisfies(transaction -> {
            assertThat(transaction.txId()).isEqualTo(InboundTransaction.txIdFor(E2E_ID));
            assertThat(transaction.endToEndId()).isEqualTo(E2E_ID);
            assertThat(transaction.creditorAccountId()).isEqualTo(PAYEE_ACCOUNT);
            assertThat(transaction.clearingAccountId()).isEqualTo(CLEARING);
            assertThat(transaction.amountCents()).isEqualTo(AMOUNT);
            assertThat(transaction.payerName()).isEqualTo("External Payer");
            assertThat(transaction.receivedAt()).isEqualTo(NOW);
        });

        assertThat(transactions.events()).singleElement().satisfies(event -> {
            assertThat(event.eventType()).isEqualTo("PixReceived");
            // The routing field step 38/39 needs: whose stream this push belongs on.
            assertThat(event.payload()).containsEntry("creditorAccountId", PAYEE_ACCOUNT);
            assertThat(event.payload()).containsEntry("amountCents", AMOUNT);
            assertThat(event.payload()).containsEntry("direction", "INBOUND");
            assertThat(event.payload()).containsEntry("status", "RECEIVED_SETTLED");
        });
    }

    /**
     * The ordering argument of the class javadoc, made a test: the credit posting runs <b>before</b> the
     * conditional record. It is safe because the posting is idempotent by {@code txId}, and it is
     * necessary because the opposite order would mark a payment handled whose money never arrived.
     */
    @Test
    void postsTheCreditBeforeRecordingSoACrashInBetweenLosesNothing() {
        useCase.execute(command(), TOKEN);

        assertThat(trace).containsExactly(
                "keys.resolve", "ledger.creditInbound", "transactions.recordReceived");
    }

    /** The dedupe: a redelivered endToEndId is acked, and no second credit is recorded. */
    @Test
    void aRedeliveredEndToEndIdIsAckedWithoutASecondCredit() {
        assertThat(useCase.execute(command(), TOKEN)).isEqualTo(ReceiveInboundOutcome.CREDITED);
        assertThat(useCase.execute(command(), TOKEN)).isEqualTo(ReceiveInboundOutcome.ALREADY_PROCESSED);

        // The posting was attempted twice — the real ledger replays it as a no-op under the same txId —
        // but only ONE transaction and ONE announcement exist.
        assertThat(ledger.inboundCredits()).hasSize(2)
                .allSatisfy(posting -> assertThat(posting.txId()).isEqualTo("in-" + E2E_ID));
        assertThat(transactions.recorded()).hasSize(1);
        assertThat(transactions.events()).as("one payment, one announcement").hasSize(1);
    }

    @Test
    void refusesAMissingOrWrongTokenWithoutTouchingAnything() {
        assertThatThrownBy(() -> useCase.execute(command(), null))
                .isInstanceOf(InvalidWebhookTokenException.class);
        assertThatThrownBy(() -> useCase.execute(command(), "not-the-token"))
                .isInstanceOf(InvalidWebhookTokenException.class);
        // A near-miss must not pass either — the check is equality, not a prefix.
        assertThatThrownBy(() -> useCase.execute(command(), TOKEN + "x"))
                .isInstanceOf(InvalidWebhookTokenException.class);

        assertThat(trace).as("a forged webhook resolves nothing, posts nothing and records nothing")
                .isEmpty();
        assertThat(ledger.inboundCredits()).isEmpty();
        assertThat(transactions.recorded()).isEmpty();
    }

    /**
     * A service configured with no token would otherwise accept an empty header and credit money to
     * anyone. Failing closed is the only acceptable direction for a misconfiguration on this route.
     */
    @Test
    void refusesEveryCallWhenNoTokenIsConfigured() {
        for (String unconfiguredToken : new String[] {null, "", "   "}) {
            var unconfigured = new ReceiveInboundPixUseCase(keys, ledger, transactions, unconfiguredToken,
                    CLEARING, Clock.fixed(NOW, ZoneOffset.UTC));

            // Including presenting exactly what is configured — a blank secret is not a secret, and
            // "" == "" must not be a way in.
            assertThatThrownBy(() -> unconfigured.execute(command(), unconfiguredToken))
                    .as("configured token %s", unconfiguredToken == null ? "null" : "[" + unconfiguredToken + "]")
                    .isInstanceOf(InvalidWebhookTokenException.class);
        }
        assertThat(ledger.inboundCredits()).isEmpty();
        assertThat(transactions.recorded()).isEmpty();
    }

    @Test
    void refusesAnUnknownKeyPermanentlyBeforeAnyMoneyMoves() {
        var toNobody = new ReceiveInboundPixCommand(E2E_ID, "nobody@nowhere.com", AMOUNT,
                "External Payer", "99999999");

        assertThatThrownBy(() -> useCase.execute(toNobody, TOKEN))
                .isInstanceOf(InboundKeyNotFoundException.class);

        assertThat(ledger.inboundCredits()).as("a bounced payment leaves no posting").isEmpty();
        assertThat(transactions.recorded()).isEmpty();
    }

    /**
     * The distinction the whole retry contract turns on: an unreachable directory is <b>unknown</b>, not
     * "no such key". It must propagate (⇒ 503, the rail retries) rather than degrade into a permanent
     * bounce that loses a perfectly deliverable payment.
     */
    @Test
    void aDirectoryOutageIsNotConfusedWithAnUnknownKey() {
        keys.beUnavailable();

        assertThatThrownBy(() -> useCase.execute(command(), TOKEN))
                .isInstanceOf(DirectoryUnavailableException.class)
                .isNotInstanceOf(InboundKeyNotFoundException.class);

        assertThat(ledger.inboundCredits()).isEmpty();
        assertThat(transactions.recorded()).isEmpty();
    }

    /** A ledger outage records nothing locally, so the rail's retry is clean work under the same txId. */
    @Test
    void aLedgerOutageRecordsNothingSoTheRetryIsCleanWork() {
        ledger.beUnavailable();

        assertThatThrownBy(() -> useCase.execute(command(), TOKEN))
                .isInstanceOf(LedgerUnavailableException.class);

        assertThat(transactions.recorded()).isEmpty();
        assertThat(transactions.events()).isEmpty();
    }

    private static ReceiveInboundPixCommand command() {
        return new ReceiveInboundPixCommand(E2E_ID, PAYEE_KEY, AMOUNT, "External Payer", "99999999");
    }
}
