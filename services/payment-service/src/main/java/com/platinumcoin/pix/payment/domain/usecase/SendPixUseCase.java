package com.platinumcoin.pix.payment.domain.usecase;

import com.platinumcoin.pix.common.event.OutboxEvent;
import com.platinumcoin.pix.common.idempotency.CanonicalJson;
import com.platinumcoin.pix.common.metrics.PixMetrics.Outcome;
import com.platinumcoin.pix.common.metrics.PixMetrics.Stage;
import com.platinumcoin.pix.payment.domain.exception.FraudDeniedException;
import com.platinumcoin.pix.payment.domain.exception.IdempotencyKeyRequiredException;
import com.platinumcoin.pix.payment.domain.exception.IdempotencyKeyReuseException;
import com.platinumcoin.pix.payment.domain.exception.InsufficientFundsException;
import com.platinumcoin.pix.payment.domain.exception.InvalidAmountException;
import com.platinumcoin.pix.payment.domain.exception.KeyNotFoundException;
import com.platinumcoin.pix.payment.domain.exception.LedgerUnavailableException;
import com.platinumcoin.pix.payment.domain.exception.LimitExceededException;
import com.platinumcoin.pix.payment.domain.exception.RequestInProgressException;
import com.platinumcoin.pix.payment.domain.exception.TransactionWriteConflictException;
import com.platinumcoin.pix.payment.domain.exception.UnresolvedOperationException;
import com.platinumcoin.pix.payment.domain.model.FraudDecision;
import com.platinumcoin.pix.payment.domain.model.IdempotencyRecord;
import com.platinumcoin.pix.payment.domain.model.IdempotencyStatus;
import com.platinumcoin.pix.payment.domain.model.KeyResolution;
import com.platinumcoin.pix.payment.domain.model.LedgerOutcome;
import com.platinumcoin.pix.payment.domain.model.LimitDecision;
import com.platinumcoin.pix.payment.domain.model.Money;
import com.platinumcoin.pix.payment.domain.model.Transaction;
import com.platinumcoin.pix.payment.domain.model.TransactionDirection;
import com.platinumcoin.pix.payment.domain.model.TransactionStatus;
import com.platinumcoin.pix.payment.domain.port.AccountLimitClient;
import com.platinumcoin.pix.payment.domain.port.DailyLimitReservation;
import com.platinumcoin.pix.payment.domain.port.FraudScorer;
import com.platinumcoin.pix.payment.domain.port.IdempotencyRepository;
import com.platinumcoin.pix.payment.domain.port.LedgerClient;
import com.platinumcoin.pix.payment.domain.port.PaymentFunnelMetrics;
import com.platinumcoin.pix.payment.domain.port.PixKeyResolver;
import com.platinumcoin.pix.payment.domain.port.TransactionRepository;
import com.platinumcoin.pix.payment.domain.service.EndToEndIdGenerator;
import com.platinumcoin.pix.payment.domain.service.PixOutboxEvents;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Accept a send-Pix request idempotently: the single business operation of {@code POST /v1/payments/pix}.
 * This is the API-layer answer to "the user tapped twice / the network retried" (ADR-0002, layer 1) —
 * it wraps the actual acceptance work in a claim/replay lifecycle so a retried request never mints a
 * second transaction.
 *
 * <p><b>The lifecycle (ADR-0002, amended by ADR-0014).</b> Validate the amount first (a malformed
 * request must never leave an idempotency record behind), compute the request-hash over the normalized
 * fields, <b>mint the operation's identity</b> ({@code txId} + {@code endToEndId}), then:
 * <ol>
 *   <li><b>claim</b> — a conditional put wins or loses atomically, <b>and writes that identity</b>.
 *       Win ⇒ do the money-moving work
 *       (resolve the destination key, reserve the daily limit, command the atomic ledger debit/credit,
 *       persist the state the money is actually in — {@code SETTLED} for an internal destination,
 *       {@code DEBITED} for an external one), memoize the response, return a fresh outcome.</li>
 *   <li><b>lose</b> — a live record already exists; load it and decide from its hash and status:
 *       <ul>
 *         <li>different hash ⇒ {@link IdempotencyKeyReuseException} ({@code 409}).</li>
 *         <li>same hash, {@code COMPLETED} ⇒ {@link SendPixOutcome.Replayed} (the memoized response).</li>
 *         <li>same hash, non-terminal and fresh ⇒ {@link RequestInProgressException} ({@code 409}
 *             + Retry-After).</li>
 *         <li>same hash, non-terminal but <b>stale</b> (claimed &gt; {@value #STALE_SECONDS}s ago,
 *             i.e. a crash left the claim orphaned) ⇒ conditionally re-claim and redo the work
 *             <b>under the identity the record already carries</b>, so a crash never blocks the client
 *             until the 24h TTL <i>and</i> never mints a second name for the same money.</li>
 *         <li>expired but still non-terminal, or carrying no identity at all ⇒
 *             {@link UnresolvedOperationException} ({@code 409}) — see {@code execute}.</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <p><b>The ordering to read here is identity → claim → effect</b> (ADR-0014). The {@code txId} is not
 * minted inside the money-moving work any more; it is minted before the claim and persisted by it, so
 * a crash anywhere after the claim is recoverable to the <i>same</i> identity rather than to a fresh
 * one the ledger's {@code attribute_not_exists(txId)} guard has never seen.
 *
 * <p>Everything that is a <i>decision</i> lives here rather than in the controller (ADR-0011): amount
 * parsing (which enforces the strictly-positive money rule), id generation, reading the injected
 * {@link Clock}, and the whole idempotency verdict. The controller only binds the request (including the
 * raw header) and renders the outcome.
 */
public class SendPixUseCase {

    private static final Logger log = LoggerFactory.getLogger(SendPixUseCase.class);

    /**
     * A non-terminal claim older than this is treated as crash-orphaned and re-claimable — far beyond
     * any legitimate in-flight send, well below the 24h replay TTL (ADR-0002). Shortening it was
     * considered and rejected as a fix for the crash window (ADR-0014): any non-zero window still
     * double-debits without a durable identity, and a shorter one re-claims genuinely slow requests.
     */
    static final long STALE_SECONDS = 60;

    /**
     * Bound the claim/get/reclaim loop: in practice one pass decides everything; a second pass only
     * happens if a racing writer changes the record between our claim-loss and our read. A third is
     * pathological, so we stop and report in-progress rather than spin.
     */
    private static final int MAX_ATTEMPTS = 3;

    private static final int ACCEPTED_HTTP_STATUS = 202;

    /**
     * The zone whose calendar day the daily limit is windowed on. America/São Paulo, matching how Pix
     * limits are communicated to Brazilian users (a "daily" limit rolls over at local midnight, not
     * UTC). Reading the clock and resolving it to a day is the use case's job (ADR-0011), so the day
     * boundary is a value a test can pin via the injected {@link Clock}.
     */
    private static final ZoneId LIMIT_ZONE = ZoneId.of("America/Sao_Paulo");

    private final TransactionRepository transactions;
    private final IdempotencyRepository idempotency;
    private final PixKeyResolver pixKeys;
    private final AccountLimitClient accountLimits;
    private final DailyLimitReservation dailyLimits;
    private final FraudScorer fraudScorer;
    private final LedgerClient ledger;
    private final EndToEndIdGenerator endToEndIds;
    private final PaymentFunnelMetrics funnel;
    private final String clearingAccountId;

    /**
     * How many times ONE operation may be POSTed to the ledger before the platform gives up and calls the
     * outcome unresolved (ADR-0015 §4). The default is 2: the original call plus a single resolving
     * re-POST under the same {@code txId}. It is a total, not a count of extra tries, because what is
     * being bounded is how long a user's request may sit here — every attempt costs a full ledger
     * round trip on a dependency that is already misbehaving.
     */
    private final int ledgerAttempts;

    /** Pause between an ambiguous attempt and the re-POST that resolves it; zero in the unit tests. */
    private final Duration ledgerBackoff;

    private final Clock clock;

    public SendPixUseCase(
            TransactionRepository transactions,
            IdempotencyRepository idempotency,
            PixKeyResolver pixKeys,
            AccountLimitClient accountLimits,
            DailyLimitReservation dailyLimits,
            FraudScorer fraudScorer,
            LedgerClient ledger,
            EndToEndIdGenerator endToEndIds,
            PaymentFunnelMetrics funnel,
            String clearingAccountId,
            int ledgerAttempts,
            Duration ledgerBackoff,
            Clock clock) {
        this.transactions = transactions;
        this.idempotency = idempotency;
        this.pixKeys = pixKeys;
        this.accountLimits = accountLimits;
        this.dailyLimits = dailyLimits;
        this.fraudScorer = fraudScorer;
        this.ledger = ledger;
        this.endToEndIds = endToEndIds;
        this.funnel = funnel;
        this.clearingAccountId = clearingAccountId;
        this.ledgerAttempts = ledgerAttempts;
        this.ledgerBackoff = ledgerBackoff;
        this.clock = clock;
    }

    /**
     * Accept the send idempotently and return whether the transaction was freshly accepted or the
     * response was replayed.
     *
     * @throws com.platinumcoin.pix.payment.domain.exception.InvalidAmountException the amount is not strictly
     *                                                                    positive money
     * @throws IdempotencyKeyRequiredException the {@code Idempotency-Key} was absent/blank
     * @throws IdempotencyKeyReuseException    the key was replayed with a different payload
     * @throws RequestInProgressException      a concurrent request with the same key is in flight
     * @throws com.platinumcoin.pix.payment.domain.exception.KeyNotFoundException      the destination key does not
     *                                                                       resolve at all (unknown to the DICT)
     * @throws LimitExceededException          the send would breach the debtor's daily Pix limit
     * @throws FraudDeniedException            the in-path fraud check returned {@code DENY} (limit released)
     * @throws InsufficientFundsException      the ledger refused the debit for lack of funds
     * @throws com.platinumcoin.pix.payment.domain.exception.LedgerUnavailableException the ledger refused
     *                                                                    the posting, or its outcome could
     *                                                                    not be resolved within the
     *                                                                    bounded attempts (ADR-0015)
     */
    public SendPixOutcome execute(SendPixCommand command) {
        String key = command.idempotencyKey();
        if (key == null || key.isBlank()) {
            log.warn("Send refused, the Idempotency-Key header is missing on a money-moving POST, "
                    + "returning 400 | debtorAccountId={}", command.debtorAccountId());
            funnel.stageReached(Stage.RECEIVED, Outcome.REJECTED);
            throw new IdempotencyKeyRequiredException();
        }

        // Validate money BEFORE any idempotency write: a malformed request is a client error that must
        // leave no record behind (each retry simply 400s again). It is a funnel rejection at RECEIVED
        // without ever being an acceptance — the graph's first number must count payments the platform
        // took responsibility for, not every byte that arrived at the socket.
        long amountCents;
        try {
            amountCents = Money.toCents(command.amount());
        } catch (InvalidAmountException e) {
            funnel.stageReached(Stage.RECEIVED, Outcome.REJECTED);
            throw e;
        }
        String accountId = command.debtorAccountId();
        String requestHash = requestHashOf(command);

        // Mint the operation's identity BEFORE the claim (ADR-0014). The claim below writes it, so the
        // right to execute and the name of the money are established by one conditional write and can
        // never drift apart. Everything downstream — the ledger's txId guard, BACEN's endToEndId
        // dedup, the reconciliation scan — keys off names that are durable from this point on. Minted
        // after the amount check on purpose: a malformed request must leave nothing behind at all.
        String mintedTxId = "tx-" + UUID.randomUUID();
        String mintedEndToEndId = endToEndIds.generate(clock.instant());

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            Instant now = clock.instant();
            if (idempotency.claim(accountId, key, requestHash, mintedTxId, mintedEndToEndId, now)) {
                log.info("Idempotency key claimed and the operation identity persisted with it, "
                                + "proceeding to accept the payment | debtorAccountId={} "
                                + "idempotencyKey={} txId={} endToEndId={} attempt={}",
                        accountId, key, mintedTxId, mintedEndToEndId, attempt);
                return acceptAndComplete(command, amountCents,
                        new ClaimedOperation(accountId, key, mintedTxId, mintedEndToEndId), now);
            }

            // Lost the claim: a record blocks it. Inspect it to replay, 409, or (if stale) re-claim.
            Optional<IdempotencyRecord> found = idempotency.get(accountId, key);
            if (found.isEmpty()) {
                // The record vanished between our claim-loss and this read (lazy TTL / a racing
                // reclaim). Retry the whole decision — rare, and bounded by MAX_ATTEMPTS.
                log.warn("Idempotency claim lost but record not readable, retrying the decision | "
                        + "debtorAccountId={} idempotencyKey={} attempt={}", accountId, key, attempt);
                continue;
            }
            IdempotencyRecord record = found.get();

            // The expiry verdict comes FIRST, because for an expired record the hash is irrelevant:
            // either the key is free again (so nothing about the old request binds this one), or the
            // record is a stranded money operation (so no request of any shape may proceed under it).
            if (record.expired(now)) {
                if (record.status().terminal()) {
                    // The 24h window closed on a finished payment: the key is legitimately reusable
                    // (ADR-0002). We only lost the claim to a racer that got there microseconds ago,
                    // so re-run the decision rather than guess what it wrote.
                    log.warn("Idempotency record found expired and terminal, the key is reusable, "
                                    + "retrying the decision | debtorAccountId={} idempotencyKey={} "
                                    + "expiresAt={} attempt={}",
                            accountId, key, record.expiresAt(), attempt);
                    continue;
                }
                // Expired and STILL not terminal: a money operation that never resolved, older than a
                // day — which the <5-min reconciliation SLO says cannot happen. Recycling the key here
                // would hand this client a brand-new identity for money that may already have moved,
                // i.e. exactly the double-debit ADR-0014 removes. Refuse and escalate to a human.
                log.error("Expired idempotency record is still not terminal, refusing to recycle a "
                                + "money identity, returning 409 | debtorAccountId={} idempotencyKey={} "
                                + "strandedTxId={} strandedEndToEndId={} status={} claimedAt={} "
                                + "expiresAt={}",
                        accountId, key, record.txId(), record.endToEndId(), record.status(),
                        record.claimedAt(), record.expiresAt());
                // Deliberately no funnel rejection: this key was already counted as RECEIVED when its
                // first attempt was accepted. Counting it again would report two payments arriving
                // where the user made one request — the same reason the in-progress 409 counts nothing.
                throw new UnresolvedOperationException(
                        "a previous attempt of this idempotency key never resolved");
            }

            if (!record.requestHash().equals(requestHash)) {
                log.warn("Idempotency key reused with a different payload, returning 409 | "
                                + "debtorAccountId={} idempotencyKey={} storedHash={} attemptedHash={}",
                        accountId, key, record.requestHash(), requestHash);
                // A refusal, not a replay: the client asked for a *different* payment under a used key,
                // and this one will never happen. It dies at intake.
                funnel.stageReached(Stage.RECEIVED, Outcome.REJECTED);
                throw new IdempotencyKeyReuseException();
            }

            if (record.status().terminal()) {
                Map<String, String> snapshot = record.responseSnapshot();
                log.info("Idempotency hit on a completed request, replaying the stored response | "
                                + "debtorAccountId={} idempotencyKey={} httpStatus={} transactionId={}",
                        accountId, key, record.httpStatus(), snapshot.get("transactionId"));
                // KR1.1's live evidence: a duplicate the platform absorbed. Note what is NOT incremented
                // here — no stage advances — which is what makes "0 duplicate debits" observable rather
                // than merely asserted (ADR-0002, Domain Safety Rule #2).
                funnel.idempotentReplay();
                return SendPixOutcome.replayed(
                        record.httpStatus(), snapshot.get("transactionId"), snapshot.get("endToEndId"));
            }

            // Non-terminal (CLAIMED / POSTED / RECORDED): live, or crash-orphaned.
            if (isStale(record.claimedAt(), now)) {
                if (!record.hasIdentity()) {
                    // Written before ADR-0014, so it names no money. Resuming would mean inventing an
                    // identity the ledger has never seen — the precise failure being removed — and a
                    // sandbox has no backfill worth trusting. Refuse rather than guess.
                    log.error("Stale idempotency record carries no operation identity (written before "
                                    + "ADR-0014), refusing to resume it under a guessed txId, "
                                    + "returning 409 | debtorAccountId={} idempotencyKey={} status={} "
                                    + "claimedAt={}",
                            accountId, key, record.status(), record.claimedAt());
                    throw new UnresolvedOperationException(
                            "this idempotency record predates durable operation identity");
                }
                if (idempotency.reclaim(accountId, key, requestHash, record.claimedAt(), now)) {
                    // Resume under the STORED identity, never a fresh one: if the crash happened after
                    // the ledger committed, re-posting this txId is recognised as a replay by the
                    // ledger's own guard and the payer is debited exactly once (ADR-0014).
                    log.warn("Stale idempotency claim re-claimed after a crash window, resuming under "
                                    + "the stored operation identity | debtorAccountId={} "
                                    + "idempotencyKey={} txId={} endToEndId={} lastPhase={} "
                                    + "claimedAt={} staleSeconds={}",
                            accountId, key, record.txId(), record.endToEndId(), record.status(),
                            record.claimedAt(), STALE_SECONDS);
                    return acceptAndComplete(command, amountCents,
                            new ClaimedOperation(accountId, key, record.txId(), record.endToEndId()),
                            now);
                }
                // Another retry re-claimed first — treat as in-progress.
                log.warn("Stale idempotency claim already re-claimed by a concurrent retry, "
                                + "returning 409 in-progress | debtorAccountId={} idempotencyKey={}",
                        accountId, key);
                throw new RequestInProgressException();
            }

            log.warn("A request with this Idempotency-Key is already in progress, returning 409 "
                            + "+ Retry-After | debtorAccountId={} idempotencyKey={} claimedAt={}",
                    accountId, key, record.claimedAt());
            throw new RequestInProgressException();
        }

        // Exhausted the bounded retries without a decision — report in-progress so the client retries.
        log.warn("Idempotency decision did not converge within {} attempts, returning 409 in-progress "
                + "| debtorAccountId={} idempotencyKey={}", MAX_ATTEMPTS, accountId, key);
        throw new RequestInProgressException();
    }

    /**
     * What a won claim established, carried as one value through the money-moving core: whose key it is
     * and — since ADR-0014 — what the money of this operation is called. Grouping the four is not
     * cosmetic: it makes it impossible for a method down here to have the {@code accountId} but not the
     * {@code txId}, which is how the identity used to go missing and get re-minted.
     *
     * @param txId       minted before the claim and written by it; the ledger's idempotency key
     * @param endToEndId minted at the same moment; the rail's idempotency key toward BACEN
     */
    private record ClaimedOperation(String accountId, String key, String txId, String endToEndId) {
    }

    /**
     * Command one ledger posting and <b>resolve it to a fact</b> (step 66, ADR-0015). Both money paths go
     * through here, so an internal send and an external one cannot end up holding different theories of
     * the same timeout.
     *
     * <h2>Why the resolution of "I don't know" is the same call again</h2>
     * The ledger's posting API is idempotent by {@code txId}: re-sending the identical posting either
     * commits it (it had not committed) or answers {@code replayed: true} (it had). So one call resolves
     * the ambiguity <i>and</i> completes the work — there is no query endpoint to add, no second round
     * trip on the happy path, and no race between a read that says "absent" and a write that lands
     * between the two (ADR-0015 §2). This is only safe because the {@code txId} is durable (ADR-0014):
     * re-posting a <i>fresh</i> identity would resolve one ambiguity by creating a second debit.
     *
     * <h2>What each outcome means for the payment</h2>
     * <ul>
     *   <li>{@code POSTED} — the money moved on this call. Ordinary.</li>
     *   <li>{@code REPLAYED} — the money moved on an <i>earlier</i> call under this {@code txId}. Still
     *       success (the intent holds, and the payer was debited exactly once), but on a <b>first</b>
     *       attempt it is a {@code WARN}: it means some previous attempt committed and its caller never
     *       learned so.</li>
     *   <li>{@code REFUSED} — the ledger answered and did not commit. Retry-safe, but re-posting an
     *       identical request it just rejected would only burn the budget, so it fails fast as a
     *       {@code 503}.</li>
     *   <li>{@code UNKNOWN} — try again under the same {@code txId}, up to {@link #ledgerAttempts}
     *       times. Still unknown afterwards ⇒ {@code 503} and an {@code ERROR} naming the {@code txId},
     *       with the claim left in its pre-{@code POSTED} phase so the next resume picks up the same
     *       identity and finishes the resolution. It never silently becomes a "no".</li>
     * </ul>
     *
     * @param posting the call to make and, if its answer is ambiguous, to make again unchanged
     */
    private void commandPosting(ClaimedOperation operation, Supplier<LedgerOutcome> posting) {
        String txId = operation.txId();
        LedgerOutcome outcome = posting.get();
        int attempt = 1;
        while (outcome == LedgerOutcome.UNKNOWN && attempt < ledgerAttempts) {
            log.warn("The ledger outcome is unknown — the posting may or may not have committed — so the "
                            + "SAME txId is being re-posted to resolve it (the idempotent POST is the "
                            + "query) | txId={} attempt={} of={} backoffMs={}",
                    txId, attempt, ledgerAttempts, ledgerBackoff.toMillis());
            if (!backOff()) {
                // Interrupted mid-backoff: this thread is being shut down, so it does not start another
                // remote call. The outcome stays UNKNOWN — the honest answer — and the txId survives on
                // the claim, which is what makes stopping here safe rather than lossy.
                break;
            }
            outcome = posting.get();
            attempt++;
        }

        switch (outcome) {
            case POSTED -> {
                // The ordinary path: this call moved the money. Nothing to say the adapter has not said.
            }
            case REPLAYED -> {
                if (attempt == 1) {
                    log.warn("The ledger replayed this txId on the FIRST attempt: an earlier attempt "
                                    + "under this identity had already committed the money and its "
                                    + "caller never learned so — treating it as success and counting "
                                    + "the debit once | txId={} idempotencyKey={} debtorAccountId={}",
                            txId, operation.key(), operation.accountId());
                } else {
                    log.info("The ambiguous ledger outcome resolved to a replay: the earlier attempt HAD "
                                    + "committed, so the money moved exactly once and the send proceeds "
                                    + "| txId={} idempotencyKey={} attempts={}",
                            txId, operation.key(), attempt);
                }
            }
            case REFUSED -> {
                log.warn("The ledger definitively refused the posting, nothing was debited and the same "
                                + "txId stays safe to retry, returning 503 | txId={} "
                                + "idempotencyKey={} debtorAccountId={}",
                        txId, operation.key(), operation.accountId());
                throw new LedgerUnavailableException("ledger refused the posting for txId " + txId);
            }
            case UNKNOWN -> {
                // Deliberately NOT turned into "nothing happened". The claim stays pre-POSTED carrying
                // this txId, no daily-limit headroom is handed back, and the next resume re-posts the
                // same identity — which either commits it or is told it already was.
                log.error("The ledger outcome is STILL unknown after the bounded attempts: the payer may "
                                + "or may not have been debited under this txId. Answering 503 without "
                                + "releasing the daily-limit reservation; the next attempt under this "
                                + "idempotency key resolves it by re-posting the SAME txId | txId={} "
                                + "idempotencyKey={} debtorAccountId={} attempts={}",
                        txId, operation.key(), operation.accountId(), attempt);
                throw new LedgerUnavailableException(
                        "the ledger outcome for txId " + txId + " could not be resolved");
            }
            case INSUFFICIENT_FUNDS -> {
                // The adapter translates this refusal into its own exception (it carries a 422 and a
                // limit release), so it never arrives here as a value. Named anyway: an outcome the
                // switch ignores is how a future constant silently falls through as success.
                throw new InsufficientFundsException();
            }
        }
    }

    /**
     * Pause before re-posting an ambiguous outcome; a zero backoff (the unit tests) never sleeps.
     *
     * @return {@code false} if the wait was interrupted, meaning the caller must stop resolving
     */
    private boolean backOff() {
        if (ledgerBackoff.isZero() || ledgerBackoff.isNegative()) {
            return true;
        }
        try {
            Thread.sleep(ledgerBackoff.toMillis());
            return true;
        } catch (InterruptedException e) {
            // Someone is shutting this thread down: restore the flag and stop trying to resolve, rather
            // than firing one more ledger call on a thread that is on its way out.
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Advance the claim's advisory phase (ADR-0014 §3). Deliberately fire-and-observe: the port is
     * contractually forbidden from throwing here, because by the time {@code POSTED} is written the
     * payer's money has already moved and failing the request over a bookkeeping write would turn a
     * successful payment into a client-visible error. Correctness rests on the {@code txId}, not on
     * this.
     */
    private void advancePhase(ClaimedOperation operation, IdempotencyStatus phase, Instant now) {
        idempotency.advancePhase(operation.accountId(), operation.key(), phase, now);
        log.debug("Idempotency phase advanced | debtorAccountId={} idempotencyKey={} txId={} phase={}",
                operation.accountId(), operation.key(), operation.txId(), phase);
    }

    /**
     * Do the acceptance work, memoize the response, and return the fresh outcome. This is the
     * money-moving core, run only inside a won idempotency claim so a double-tap or replay never
     * repeats it. The orchestration order is <b>resolve → limit → fraud → debit → persist</b>
     * (ARCHITECTURE §6.5/§6.6) — an unknown destination is refused before any counter is touched, fraud
     * sits between the limit reservation and the debit, and the debit is the last thing that can fail
     * before we memoize.
     *
     * <p><b>The internal/external branch (step 27) is only in the last stage.</b> Everything up to the
     * debit is identical for both destinations — that is the point: authority, limits and fraud are
     * properties of the <i>payer</i>, not of where the payee banks. Only "who receives the credit leg"
     * and "is this already final?" differ.
     */
    private SendPixOutcome acceptAndComplete(
            SendPixCommand command, long amountCents, ClaimedOperation operation, Instant now) {
        String accountId = operation.accountId();
        // 0) The funnel's entry point. Counted here rather than at the top of execute() because THIS is
        //    where a payment becomes real: a won idempotency claim, exactly once per key. Counting on
        //    every arriving request would fold retries and replays into "payments received" and make
        //    every conversion ratio below it wrong.
        funnel.stageReached(Stage.RECEIVED, Outcome.OK);

        // 1) Resolve the destination FIRST. An unresolvable key is a 422 before the limit counter is
        //    touched or any money moves — so a KEY_NOT_FOUND leaves no reservation to unwind.
        KeyResolution destination;
        try {
            destination = pixKeys.resolve(command.pixKey());
        } catch (KeyNotFoundException e) {
            // The DICT looked and said "no such key": definitive, so the payment dies at intake. A
            // *unreachable* directory is a different exception and is deliberately not counted — nothing
            // was decided there.
            funnel.stageReached(Stage.RECEIVED, Outcome.REJECTED);
            throw e;
        }
        log.info("Destination Pix key resolved | creditorKey={} internal={} creditorAccountId={} "
                        + "externalBank={} debtorAccountId={}",
                command.pixKey(), destination.internal(), destination.accountId(),
                destination.externalBank(), accountId);

        // 2) Reserve the daily-limit headroom BEFORE the debit (step 20). Inside the won-claim path on
        //    purpose: the reservation is a non-idempotent counter increment, so it must run exactly once
        //    per idempotency key — a double-tap or a replay must never reserve twice. A DENY throws here.
        reserveDailyLimit(accountId, amountCents, now);

        // 3) Screen for fraud BETWEEN the limit reservation and the debit (ADR-0005), under a hard 200ms
        //    budget the adapter enforces. A DENY releases the reservation and throws here; APPROVE/REVIEW
        //    proceed, and a timed-out/errored check proceeds fail-open as SKIPPED. The verdict is carried
        //    onto the transaction so the RECEIVED→FRAUD_CHECKED stage is durably recorded.
        FraudDecision fraudDecision = screenForFraud(accountId, command.pixKey(), amountCents, now);

        // 4) Move the money and persist the state the money is actually in. An internal transfer settles
        //    the instant the atomic ledger posting commits — there is no SPI leg — so it lands at
        //    SETTLED; an external one only reaches the clearing account, so it rests at DEBITED until
        //    settlement (steps 28-31). Either way INSUFFICIENT_FUNDS releases the reservation from step 2.
        Transaction transaction = destination.internal()
                ? settleInternally(
                        command, destination.accountId(), amountCents, operation, fraudDecision, now)
                : debitToClearing(command, amountCents, operation, fraudDecision, now);

        Map<String, String> snapshot = new LinkedHashMap<>();
        snapshot.put("transactionId", transaction.txId());
        snapshot.put("endToEndId", transaction.endToEndId());
        idempotency.complete(accountId, operation.key(), ACCEPTED_HTTP_STATUS, snapshot, clock.instant());

        log.info("Idempotency record completed and memoized for replay | debtorAccountId={} "
                        + "idempotencyKey={} transactionId={} httpStatus={}",
                accountId, operation.key(), transaction.txId(), ACCEPTED_HTTP_STATUS);
        return SendPixOutcome.accepted(transaction);
    }

    /**
     * Read the debtor's daily limit (server-side, never from the client) and reserve the amount against
     * today's calendar-day counter (ADR-0007, step 20). The decision is a three-valued object, not a
     * boolean, so the MFA seam is explicit: {@code ALLOW} proceeds; {@code DENY} and — until MFA lands —
     * {@code REQUIRE_STEP_UP} both refuse with {@link LimitExceededException} ({@code 422}). Plugging in a
     * step-up challenge later changes only the {@code REQUIRE_STEP_UP} branch, not this flow.
     */
    private void reserveDailyLimit(String accountId, long amountCents, Instant now) {
        long dailyLimitCents = accountLimits.dailyLimitCents(accountId);
        LocalDate day = now.atZone(LIMIT_ZONE).toLocalDate();

        LimitDecision decision = dailyLimits.reserve(accountId, amountCents, dailyLimitCents, day);
        if (decision == LimitDecision.ALLOW) {
            log.info("Daily-limit headroom reserved, the send may proceed | debtorAccountId={} "
                            + "amountCents={} dailyLimitCents={} day={} decision={}",
                    accountId, amountCents, dailyLimitCents, day, decision);
            return;
        }

        // DENY or REQUIRE_STEP_UP: refuse before any money moves. REQUIRE_STEP_UP is where an MFA
        // challenge would go; today it maps to the same 422 as DENY (ADR-0007).
        log.warn("Send refused, the daily Pix limit would be exceeded, returning 422 | "
                        + "debtorAccountId={} amountCents={} dailyLimitCents={} day={} decision={}",
                accountId, amountCents, dailyLimitCents, day, decision);
        // Dies at RECEIVED, not at FRAUD_CHECKED: the limit is enforced before fraud is ever consulted,
        // so counting it later would invent a stage this payment never reached.
        funnel.stageReached(Stage.RECEIVED, Outcome.REJECTED);
        throw new LimitExceededException();
    }

    /**
     * Score the send against fraud rules and return the verdict to carry onto the transaction (ADR-0005).
     * The fraud check sits between the limit reservation and the debit, so:
     * <ul>
     *   <li>{@code DENY} ⇒ <b>release the reservation</b> taken by {@link #reserveDailyLimit} and refuse
     *       with {@link FraudDeniedException} ({@code 422}) — a denied send must leave the day's counter
     *       exactly as it found it, mirroring the insufficient-funds release.</li>
     *   <li>{@code SKIPPED} ⇒ the check could not finish inside the 200ms budget and the adapter
     *       <b>failed open</b>; the send proceeds unscored and flagged, and the skip is queued for async
     *       re-scoring (outbox seam).</li>
     *   <li>{@code FRAUD_ERROR} ⇒ the check is <b>broken</b>, not slow (ADR-0018). Behaviourally
     *       identical to {@code SKIPPED} — deliberately, because a bad fraud deploy must not become a
     *       payments outage — but reported at {@code ERROR} and stamped with its own verdict, so the
     *       payments that went out unscored <i>because the control was disabled</i> are a query rather
     *       than a log search.</li>
     *   <li>{@code APPROVE}/{@code REVIEW} ⇒ proceed (a {@code REVIEW} is flagged, not blocked).</li>
     * </ul>
     * The port never throws for a slow/broken fraud-service — the fail-<i>open</i> and its classification
     * live in the adapter (it alone observes the transport fact), so this method applies a single business
     * rule to a five-valued result and advances the transaction to the {@code FRAUD_CHECKED} stage. Note
     * what the use case does <b>not</b> learn from the split: the rule is still "{@code DENY} blocks,
     * everything else proceeds". The classification is a statement to operators, not a change of policy —
     * which is why it cost this method one branch and no new decision.
     */
    private FraudDecision screenForFraud(String accountId, String pixKey, long amountCents, Instant now) {
        FraudDecision scored = fraudScorer.score(accountId, pixKey, amountCents, now);
        // A port that answers null has broken its own contract, which is precisely what FRAUD_ERROR means
        // — so normalize here rather than trusting the contract downstream. The placement is the point:
        // this is BEFORE the debit, so a misbehaving adapter refuses the score loudly instead of throwing
        // an NPE after the money has already moved and stranding a debit with no transaction row.
        FraudDecision decision = scored != null ? scored : FraudDecision.FRAUD_ERROR;
        if (scored == null) {
            log.error("The fraud port returned no decision at all, which breaks its own contract; "
                            + "treating it as a broken check and proceeding fail-open | "
                            + "debtorAccountId={} pixKey={} amountCents={}",
                    accountId, pixKey, amountCents);
        }

        // The verdict mix, recorded for every outcome including SKIPPED — the fail-open share of this
        // counter is the only place the platform reports how often the 200ms budget was blown (ADR-0005).
        funnel.fraudDecision(decision);

        if (decision == FraudDecision.DENY) {
            LocalDate day = now.atZone(LIMIT_ZONE).toLocalDate();
            dailyLimits.release(accountId, amountCents, day);
            log.warn("Fraud screening denied the send, released the daily-limit reservation, returning "
                            + "422 | debtorAccountId={} pixKey={} amountCents={} day={} decision={}",
                    accountId, pixKey, amountCents, day, decision);
            funnel.stageReached(Stage.FRAUD_CHECKED, Outcome.REJECTED);
            throw new FraudDeniedException();
        }

        if (decision == FraudDecision.FRAUD_ERROR) {
            // ADR-0018: the send proceeds EXACTLY as for a SKIPPED — same branch, same outbox marker,
            // same 202. What differs is that this one is actionable: the fraud control is disabled until
            // somebody fixes a credential or a contract, so it is stated at ERROR rather than filed under
            // "the afternoon was busy". Both classes go out unscored; only one of them will fix itself.
            log.error("Fraud check was BROKEN rather than slow, proceeding fail-open anyway (ADR-0018 "
                            + "keeps ADR-0005's availability choice), the send is unscored and flagged "
                            + "for async re-scoring | debtorAccountId={} pixKey={} amountCents={} "
                            + "fraudSkipped=true decision={}",
                    accountId, pixKey, amountCents, decision);
        } else if (decision == FraudDecision.SKIPPED) {
            // The core ADR-0005 behaviour: availability of payments chosen at this layer. The send is
            // unscored — bounded by daily limits + async re-scoring, which the skip marker below triggers.
            log.warn("Fraud check skipped (the 200ms budget expired or fraud-service was unreachable), "
                            + "proceeding fail-open, the send is unscored and flagged | "
                            + "debtorAccountId={} pixKey={} amountCents={} fraudSkipped=true decision={}",
                    accountId, pixKey, amountCents, decision);
            // The skip is not forgotten: it becomes a FraudCheckSkipped outbox event written in the
            // same atomic transaction as the payment (step 28), so async re-scoring cannot miss it
            // even if this process dies right after the debit. A FRAUD_ERROR takes the same seam.
        } else {
            log.info("Fraud check scored the payment and cleared it to proceed | debtorAccountId={} "
                            + "pixKey={} amountCents={} decision={}",
                    accountId, pixKey, amountCents, decision);
        }

        log.info("Fraud stage advanced the transaction RECEIVED->FRAUD_CHECKED | debtorAccountId={} "
                        + "pixKey={} decision={} fraudSkipped={}",
                accountId, pixKey, decision, decision.wentUnscored());
        // A skip ADVANCES the funnel, and so does a FRAUD_ERROR: fail-open means the payment proceeds.
        // Both are visible as risk in the decision mix above, never as a drop-off here — "how many
        // payments died?" and "how many went out unscored?" are different questions, and answering the
        // second in the first one's counter would report a working platform as a broken one.
        funnel.stageReached(Stage.FRAUD_CHECKED, Outcome.OK);
        return decision;
    }

    /**
     * Command the atomic ledger debit/credit under the claim's identity, and persist the settled
     * transaction. For an internal transfer the single {@code TransactWriteItems} <i>is</i> the settlement, so the terminal
     * state is {@code SETTLED} with {@code settledAt} stamped at the same instant the money moved
     * (Domain Safety Rule #4). The ledger is keyed by {@code txId} (Domain Safety Rule #2), so a retry
     * after a crash replays the committed posting rather than double-debiting.
     *
     * <p>On {@link InsufficientFundsException} the ledger wrote nothing — the guard lives inside its
     * transaction — so the daily-limit reservation taken in step 2 is <b>released</b> before the failure
     * propagates as a {@code 422}. A {@link LedgerUnavailableException} is <i>not</i> released, and since
     * step 66 that asymmetry has a second, stronger reason than the first: the send may have been
     * refused (nothing debited) <i>or</i> its outcome may be unresolved (the payer may already have
     * paid), and handing back headroom for a debit that possibly happened is the same error mirrored.
     * The client retries the same idempotency key, which re-drives this whole path under the same
     * {@code txId} (the reservation is honoured by that retry). Leaving the record non-terminal accepts
     * the conservative over-count edge ADR-0007/step 20 already documents — never overspend, self-heals
     * next calendar day.
     */
    private Transaction settleInternally(
            SendPixCommand command, String creditorAccountId, long amountCents,
            ClaimedOperation operation, FraudDecision fraudDecision, Instant now) {
        // No id is minted here any more (ADR-0014): both come from the claim, so a resume of a crashed
        // attempt re-posts the identity the ledger may already hold instead of inventing a second one.
        String accountId = operation.accountId();
        String txId = operation.txId();
        String endToEndId = operation.endToEndId();
        String description = command.description() == null ? "" : command.description();

        log.info("Commanding the ledger to debit the payer and credit the payee atomically | txId={} "
                        + "endToEndId={} debtorAccountId={} creditorAccountId={} amountCents={}",
                txId, endToEndId, accountId, creditorAccountId, amountCents);
        try {
            commandPosting(operation, () -> ledger.postInternalTransfer(
                    txId, accountId, creditorAccountId, amountCents, description));
        } catch (InsufficientFundsException e) {
            releaseAfterInsufficientFunds(txId, accountId, amountCents, now);
            throw e;
        }
        // The atomic posting committed: the payer's money moved (Domain Safety Rule #4). Counted after
        // the call returns, never before — an optimistic increment would report money that did not move.
        funnel.stageReached(Stage.DEBITED, Outcome.OK);
        advancePhase(operation, IdempotencyStatus.POSTED, now);

        // Internal transfer: the posting committed, so it is settled now. The fraud verdict rides along
        // (fraudSkipped is its boolean shorthand — TRUE for both unscored classes, ADR-0018) so the
        // FRAUD_CHECKED stage is durable on the item.
        Transaction transaction = new Transaction(
                txId,
                endToEndId,
                // This service only ever writes sends; the arrivals settlement-service writes into the
                // same table are read-only here (step 45).
                TransactionDirection.OUTBOUND,
                accountId,
                command.pixKey(),
                creditorAccountId,
                true,
                null, // internal: no clearing account, the money reached the payee directly
                amountCents,
                TransactionStatus.SETTLED,
                description,
                fraudDecision,
                fraudDecision.wentUnscored(),
                now,
                now,
                null);   // a send this service accepts has not failed; only a reversal carries a reason
        persistWithOutbox(operation, transaction, now);

        log.info("Internal Pix moved money and settled, persisted as SETTLED, returning 202 Accepted | "
                        + "txId={} status={} settledAt={}",
                txId, transaction.status(), transaction.settledAt());
        // An internal send is terminal here — no SPI leg — so this service closes the funnel itself and
        // owns the settled volume. The external branch deliberately does neither: settlement-service does
        // (step 44, task 1), because only BACEN can say the money arrived.
        funnel.stageReached(Stage.SETTLED, Outcome.OK);
        funnel.settled(amountCents);
        return transaction;
    }

    /**
     * The <b>external</b> half of the branch (step 27): command, under the claim's identity, the atomic posting
     * <i>debit payer / credit the clearing account</i>, and persist the transaction as {@code DEBITED}.
     *
     * <p><b>Why the credit leg is an internal clearing account.</b> No ACID transaction can span
     * PlatinumCoin and another PSP, so the money cannot be handed to the payee here. It is instead
     * debited from the payer and parked in {@code ACCOUNT#SPI_CLEARING} — money in flight, owned by no
     * user — which keeps double-entry symmetry intact: the posting is balanced, and {@code Σ balances}
     * is exactly what it was a microsecond earlier (ARCHITECTURE §4). The clearing account is an
     * <b>injected id</b>, never a literal, so step 52 can shard it ({@code SPI_CLEARING#00..#15}) without
     * touching this orchestration.
     *
     * <p><b>Why the status is {@code DEBITED} and not {@code SETTLED}.</b> The payer's money is gone but
     * the payee has not been paid; only BACEN can close that gap. Claiming {@code SETTLED} here would be
     * a lie the client could act on. The transaction therefore rests in {@code DEBITED} — the state the
     * settlement consumer (step 31) advances and the reconciliation scan (step 34) hunts for when it
     * dwells too long. Nothing is published yet: the outbox event that triggers settlement is written
     * atomically with this transaction in step 28.
     *
     * <p>Failure handling is identical to the internal path — the guard lives inside the ledger's
     * transaction, so an {@link InsufficientFundsException} means nothing moved (release the
     * reservation, {@code 422}), while a {@code LedgerUnavailableException} means the posting was
     * refused <i>or</i> its outcome is unresolved ({@code 503}, retry-safe under the same idempotency
     * key, no headroom handed back — step 66, ADR-0015).
     */
    private Transaction debitToClearing(
            SendPixCommand command, long amountCents, ClaimedOperation operation,
            FraudDecision fraudDecision, Instant now) {
        // Same rule as the internal path: the identity comes from the claim, never from here.
        String accountId = operation.accountId();
        String txId = operation.txId();
        String endToEndId = operation.endToEndId();
        String description = command.description() == null ? "" : command.description();

        log.info("Commanding the ledger to debit the payer and park the money in the clearing account "
                        + "atomically (external destination, no ACID transaction spans two banks) | "
                        + "txId={} endToEndId={} debtorAccountId={} clearingAccountId={} amountCents={} "
                        + "creditorKey={}",
                txId, endToEndId, accountId, clearingAccountId, amountCents, command.pixKey());
        try {
            commandPosting(operation, () -> ledger.postExternalDebitToClearing(
                    txId, accountId, clearingAccountId, amountCents, description));
        } catch (InsufficientFundsException e) {
            releaseAfterInsufficientFunds(txId, accountId, amountCents, now);
            throw e;
        }
        funnel.stageReached(Stage.DEBITED, Outcome.OK);
        advancePhase(operation, IdempotencyStatus.POSTED, now);

        // The money left the payer but has NOT reached the other PSP: DEBITED, and settledAt stays null
        // until settlement confirms it (step 31). There is no creditorAccountId — the payee banks
        // elsewhere — which is exactly what creditorInternal=false records.
        Transaction transaction = new Transaction(
                txId,
                endToEndId,
                // This service only ever writes sends; the arrivals settlement-service writes into the
                // same table are read-only here (step 45).
                TransactionDirection.OUTBOUND,
                accountId,
                command.pixKey(),
                null,
                false,
                clearingAccountId, // the exact account the debit credited; a reversal must target it (step 33)
                amountCents,
                TransactionStatus.DEBITED,
                description,
                fraudDecision,
                fraudDecision.wentUnscored(),
                now,
                null,
                null);   // idem: settlement-service stamps the reason if BACEN ever refuses
        persistWithOutbox(operation, transaction, now);

        log.info("External Pix debited the payer to the clearing account, persisted as DEBITED awaiting "
                        + "settlement, returning 202 Accepted | txId={} status={} clearingAccountId={} "
                        + "endToEndId={}",
                txId, transaction.status(), clearingAccountId, endToEndId);
        return transaction;
    }

    /**
     * Persist the transaction <b>and the events it announces</b> in one atomic write (step 28,
     * ADR-0004).
     *
     * <p>This is where the flow stops being purely synchronous. Announcing what happened cannot be a
     * second step after saving it: a crash between the two would either lose the event — for an
     * external send that means money sitting in the clearing account with nobody left to settle it — or
     * announce a payment that never committed. Writing the events as items in the transaction's own
     * partition makes both a single commit, and turns delivery into a separate, retryable problem that
     * a lost publish cannot corrupt (step 29 drains the outbox; consumers dedupe by {@code eventId}).
     *
     * <p>Which events those are is {@link PixOutboxEvents}' decision, not this method's: an internal
     * send is already settled and announces {@code PixSettled}, an external one announces
     * {@code PixDebited}, and a fail-open fraud skip rides along in the same write.
     *
     * <p><b>The write is idempotent too, and it has to be (ADR-0014).</b> Once the identity is durable,
     * a resume re-runs this method with the {@code txId} its earlier attempt may already have written,
     * and {@code attribute_not_exists(pk)} refuses it. Treating that refusal as an error would strand
     * the client: the transaction exists, the money moved, and re-creating it is impossible by
     * construction — the only way out is forward, to the memo the client never received. So a conflict
     * here is recognised as "my own earlier attempt" and the flow continues. Making the resume reuse
     * the identity without also making this write tolerate it would be half a fix.
     */
    private void persistWithOutbox(ClaimedOperation operation, Transaction transaction, Instant now) {
        List<OutboxEvent> events = PixOutboxEvents.forAcceptedSend(transaction, now);
        try {
            transactions.create(transaction, events);
            log.info("Transaction and its outbox events committed atomically, awaiting the publisher | "
                            + "txId={} status={} events={}",
                    transaction.txId(), transaction.status(),
                    events.stream().map(OutboxEvent::eventType).toList());
        } catch (TransactionWriteConflictException conflict) {
            // A txId is minted once per idempotency claim and never shared between operations, so an
            // existing TX# item can only be this operation's own earlier attempt. That is an argument,
            // not a proof — and this is the money path, so read the item back and CHECK before acting
            // on it. The cost is one extra read on a cold recovery path and nothing on the happy path.
            Transaction alreadyWritten = transactions.findById(transaction.txId())
                    .orElseThrow(() -> conflict);
            // The freshly built transaction is on the LEFT because it is the operand guaranteed non-null:
            // `debtorAccountId` became nullable in step 45 (an inbound arrival has none), and although a
            // txId collision between `tx-<uuid>` and `in-<endToEndId>` is impossible by construction,
            // "impossible by construction" is the argument this very block refuses to rely on. A null
            // here must be a refusal, never an NPE that turns a conflict into a 500.
            if (alreadyWritten.amountCents() != transaction.amountCents()
                    || !transaction.debtorAccountId().equals(alreadyWritten.debtorAccountId())) {
                log.error("Transaction id already exists but describes a different operation, refusing "
                                + "to treat it as a resume | txId={} storedDebtorAccountId={} "
                                + "storedAmountCents={} attemptedDebtorAccountId={} "
                                + "attemptedAmountCents={}",
                        transaction.txId(), alreadyWritten.debtorAccountId(),
                        alreadyWritten.amountCents(), transaction.debtorAccountId(),
                        transaction.amountCents());
                throw conflict;
            }
            // Proven to be ours: the earlier attempt got the transaction and its events committed and
            // then died. Writing nothing is exactly right — a second create would duplicate the outbox
            // events and have consumers act twice on money that moved once.
            log.warn("Transaction was already recorded by an earlier attempt of this same operation, "
                            + "skipping the write and resuming at the memo | txId={} status={} "
                            + "amountCents={} debtorAccountId={}",
                    transaction.txId(), alreadyWritten.status(), alreadyWritten.amountCents(),
                    alreadyWritten.debtorAccountId());
        }
        advancePhase(operation, IdempotencyStatus.RECORDED, now);
    }

    /**
     * Unwind the daily-limit reservation after the ledger refused the debit for lack of funds. The
     * guard lives <i>inside</i> the ledger transaction (Domain Safety Rule #3), so a refusal means no
     * money moved at all and the headroom this send reserved must go back — a rejected send has to leave
     * the day's counter exactly as it found it. Shared by both destinations because the unwinding is a
     * property of the payer's failed debit, not of where the payee banks.
     */
    private void releaseAfterInsufficientFunds(
            String txId, String accountId, long amountCents, Instant now) {
        LocalDate day = now.atZone(LIMIT_ZONE).toLocalDate();
        dailyLimits.release(accountId, amountCents, day);
        log.warn("Ledger refused the debit for insufficient funds, released the daily-limit "
                        + "reservation, returning 422 | txId={} debtorAccountId={} amountCents={} day={}",
                txId, accountId, amountCents, day);
        // The ledger's own verdict, reached inside its atomic write — definitive, so the payment dies at
        // DEBITED. Shared by both destinations because a refused debit is a property of the payer, not of
        // where the payee banks.
        funnel.stageReached(Stage.DEBITED, Outcome.REJECTED);
    }

    /**
     * The request-hash: canonical-JSON SHA-256 over the normalized request fields (Domain Safety Rule
     * #2). The debtor is <i>not</i> hashed — the record is already scoped per account by its key — so
     * the hash is exactly "the same operation": destination, amount, description. A missing description
     * is normalized to {@code ""} so its presence/absence never changes the verdict.
     */
    private static String requestHashOf(SendPixCommand command) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("pixKey", command.pixKey());
        fields.put("amount", command.amount());
        fields.put("description", command.description() == null ? "" : command.description());
        return CanonicalJson.hash(fields);
    }

    private static boolean isStale(Instant claimedAt, Instant now) {
        return Duration.between(claimedAt, now).getSeconds() > STALE_SECONDS;
    }
}
