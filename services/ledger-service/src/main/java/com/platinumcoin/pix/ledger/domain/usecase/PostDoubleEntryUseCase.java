package com.platinumcoin.pix.ledger.domain.usecase;

import com.platinumcoin.pix.ledger.domain.InvalidPostingException;
import com.platinumcoin.pix.ledger.domain.LedgerRepository;
import com.platinumcoin.pix.ledger.domain.PostingCommand;
import com.platinumcoin.pix.ledger.domain.PostingResult;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Post one double-entry transaction: debit one account, credit another, write both immutable legs —
 * <b>all four writes in a single DynamoDB transaction</b> (ADR-0001, ARCHITECTURE §6.3). This is the
 * operation the whole platform is built around, and the direct answer to "how do you guarantee money
 * is never debited without being credited": there is no code path that writes one leg, because there
 * is no code path that writes anything except the whole transaction.
 *
 * <p>What this use case owns, and what it deliberately does not:
 *
 * <ul>
 *   <li><b>It owns validity.</b> A non-positive amount, a blank identity and a self-posting are
 *       refused here, before any port is touched, so a nonsense command never reaches DynamoDB.</li>
 *   <li><b>It owns the clock</b> (ADR-0011). {@code Clock} is injected and read exactly once per
 *       posting; the instant becomes part of both ENTRY sort keys, so it is a value the ledger
 *       decides, not something an adapter picks up on the way to the wire.</li>
 *   <li><b>It does not own the guards.</b> "Enough funds" and "not already posted" are conditions
 *       evaluated <i>inside</i> the transaction. Checking them here first would be a read-then-check
 *       race: the answer could be stale by the time the write lands, which is exactly the bug the
 *       conditional write exists to make impossible (domain safety rule 3).</li>
 * </ul>
 */
public class PostDoubleEntryUseCase {

    private static final Logger log = LoggerFactory.getLogger(PostDoubleEntryUseCase.class);

    private final LedgerRepository ledger;
    private final Clock clock;

    public PostDoubleEntryUseCase(LedgerRepository ledger, Clock clock) {
        this.ledger = ledger;
        this.clock = clock;
    }

    public PostingResult execute(PostingCommand request) {
        log.info("Ledger posting requested | txId={} debitAccount={} creditAccount={} amountCents={} "
                        + "entryType={} description={}",
                request.txId(), request.debitAccount(), request.creditAccount(), request.amountCents(),
                request.entryType(), request.description());

        validate(request);
        PostingCommand command = request.normalized();

        // Milliseconds, not nanoseconds: this instant is written into the ENTRY sort keys with
        // millisecond precision, and a result that claimed more precision than its own key carries
        // would be a small lie that a statement reconciliation would eventually have to explain.
        Instant postedAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);

        PostingResult result = ledger.post(command, postedAt);

        if (result.replayed()) {
            log.info("Ledger posting was already committed under this txId, returning the stored "
                            + "posting unchanged (idempotent replay, no money moved twice) "
                            + "| txId={} debitAccount={} creditAccount={} amountCents={} postedAt={}",
                    result.txId(), result.command().debitAccount(), result.command().creditAccount(),
                    result.command().amountCents(), result.postedAt());
        } else {
            log.info("Ledger posting committed atomically, both balances moved and both entries "
                            + "written | txId={} debitAccount={} creditAccount={} amountCents={} postedAt={}",
                    result.txId(), result.command().debitAccount(), result.command().creditAccount(),
                    result.command().amountCents(), result.postedAt());
        }
        return result;
    }

    /**
     * Everything that makes a command impossible rather than merely unlucky. Each rejection logs the
     * offending value (sandbox data, ADR-0012) so "why was my posting refused" is answerable from the
     * log alone, and each throws before the port is called — the tests assert exactly that.
     */
    private static void validate(PostingCommand command) {
        if (isBlank(command.txId())) {
            // Bracketed so a blank is visibly a blank rather than an empty-looking log field.
            reject("the txId is blank, so the posting has no identity to be idempotent on",
                    command, "rawTxId=[" + command.txId() + "]");
        }
        if (isBlank(command.debitAccount()) || isBlank(command.creditAccount())) {
            reject("one of the accounts is blank", command,
                    "debitAccount=" + command.debitAccount() + " creditAccount=" + command.creditAccount());
        }
        if (isBlank(command.entryType())) {
            reject("the entryType is blank, so the entries would carry no reason for the movement",
                    command, "rawEntryType=[" + command.entryType() + "]");
        }
        if (command.amountCents() <= 0) {
            // A negative amount is not a reversal. Reversals are compensating postings with the legs
            // swapped (domain safety rule 5) — an entry is never rewritten, and a debit of minus one
            // cent is a credit wearing a disguise.
            reject("the amount is not positive, and a posting always moves a positive amount from "
                            + "one side to the other", command, "amountCents=" + command.amountCents());
        }
        if (command.debitAccount().equals(command.creditAccount())) {
            reject("both legs name the same account, which moves no money and cannot be expressed as "
                            + "one transaction (two operations on one item)", command,
                    "account=" + command.debitAccount());
        }
    }

    private static void reject(String reason, PostingCommand command, String values) {
        log.warn("Ledger posting refused before any write, {}, returning 422 | txId={} {}",
                reason, command.txId(), values);
        throw new InvalidPostingException("Invalid posting: " + reason + ".");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
