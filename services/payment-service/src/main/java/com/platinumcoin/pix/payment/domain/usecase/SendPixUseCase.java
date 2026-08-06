package com.platinumcoin.pix.payment.domain.usecase;

import com.platinumcoin.pix.payment.domain.EndToEndIdGenerator;
import com.platinumcoin.pix.payment.domain.Money;
import com.platinumcoin.pix.payment.domain.Transaction;
import com.platinumcoin.pix.payment.domain.TransactionRepository;
import com.platinumcoin.pix.payment.domain.TransactionStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Accept a send-Pix request: the single business operation of {@code POST /v1/payments/pix} in this
 * walking skeleton. It does exactly the acceptance work and nothing downstream — parse and validate
 * the amount, mint the ids, stamp the clock, persist the transaction as {@code RECEIVED}. Idempotency
 * (step 19), the daily-limit reservation (step 20), key resolution and the atomic ledger debit
 * (step 21) thicken this method in later steps.
 *
 * <p>Everything that is a <i>decision</i> lives here rather than in the controller (ADR-0011): the
 * string→cents conversion (which enforces the strictly-positive money rule), the id generation, and
 * reading the clock through the injected {@link Clock} (never {@code Instant.now()}), so the instant a
 * transaction is stamped with is a value a test can pin.
 *
 * <p>The debtor account arrives already resolved from the JWT — this use case takes it as an input and
 * has no other way to learn it, which is the domain-level half of "the debited account is never from
 * the payload".
 */
public class SendPixUseCase {

    private static final Logger log = LoggerFactory.getLogger(SendPixUseCase.class);

    private final TransactionRepository transactions;
    private final EndToEndIdGenerator endToEndIds;
    private final Clock clock;

    public SendPixUseCase(TransactionRepository transactions, EndToEndIdGenerator endToEndIds, Clock clock) {
        this.transactions = transactions;
        this.endToEndIds = endToEndIds;
        this.clock = clock;
    }

    /**
     * Validate, mint ids, persist as {@code RECEIVED}, and return the accepted transaction.
     *
     * @throws com.platinumcoin.pix.payment.domain.InvalidAmountException the amount is not strictly
     *                                                                    positive money
     */
    public Transaction execute(SendPixCommand command) {
        long amountCents = Money.toCents(command.amount());
        Instant now = clock.instant();
        String txId = "tx-" + UUID.randomUUID();
        String endToEndId = endToEndIds.generate(now);
        String description = command.description() == null ? "" : command.description();

        log.info("Pix send accepted, generating ids and persisting it as RECEIVED before any money "
                        + "moves | txId={} endToEndId={} debtorAccountId={} creditorKey={} amountCents={}",
                txId, endToEndId, command.debtorAccountId(), command.pixKey(), amountCents);

        Transaction transaction = new Transaction(
                txId,
                endToEndId,
                command.debtorAccountId(),
                command.pixKey(),
                amountCents,
                TransactionStatus.RECEIVED,
                description,
                now);
        transactions.create(transaction);

        log.info("Pix send transaction persisted, returning 202 Accepted | txId={} status={}",
                txId, transaction.status());
        return transaction;
    }
}
