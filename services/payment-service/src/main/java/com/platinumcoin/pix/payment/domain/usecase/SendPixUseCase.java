package com.platinumcoin.pix.payment.domain.usecase;

import com.platinumcoin.pix.common.idempotency.CanonicalJson;
import com.platinumcoin.pix.payment.domain.AccountLimitClient;
import com.platinumcoin.pix.payment.domain.DailyLimitReservation;
import com.platinumcoin.pix.payment.domain.EndToEndIdGenerator;
import com.platinumcoin.pix.payment.domain.IdempotencyKeyRequiredException;
import com.platinumcoin.pix.payment.domain.IdempotencyKeyReuseException;
import com.platinumcoin.pix.payment.domain.IdempotencyRecord;
import com.platinumcoin.pix.payment.domain.IdempotencyRepository;
import com.platinumcoin.pix.payment.domain.IdempotencyStatus;
import com.platinumcoin.pix.payment.domain.InsufficientFundsException;
import com.platinumcoin.pix.payment.domain.LedgerClient;
import com.platinumcoin.pix.payment.domain.LimitDecision;
import com.platinumcoin.pix.payment.domain.LimitExceededException;
import com.platinumcoin.pix.payment.domain.Money;
import com.platinumcoin.pix.payment.domain.PixKeyResolver;
import com.platinumcoin.pix.payment.domain.RequestInProgressException;
import com.platinumcoin.pix.payment.domain.Transaction;
import com.platinumcoin.pix.payment.domain.TransactionRepository;
import com.platinumcoin.pix.payment.domain.TransactionStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Accept a send-Pix request idempotently: the single business operation of {@code POST /v1/payments/pix}.
 * This is the API-layer answer to "the user tapped twice / the network retried" (ADR-0002, layer 1) —
 * it wraps the actual acceptance work in a claim/replay lifecycle so a retried request never mints a
 * second transaction.
 *
 * <p><b>The lifecycle (ADR-0002).</b> Validate the amount first (a malformed request must never leave
 * an idempotency record behind), compute the request-hash over the normalized fields, then:
 * <ol>
 *   <li><b>claim</b> — a conditional put wins or loses atomically. Win ⇒ do the money-moving work
 *       (resolve the destination key, reserve the daily limit, command the atomic ledger debit/credit,
 *       persist {@code SETTLED}), memoize the response, return a fresh outcome.</li>
 *   <li><b>lose</b> — a live record already exists; load it and decide from its hash and status:
 *       <ul>
 *         <li>different hash ⇒ {@link IdempotencyKeyReuseException} ({@code 409}).</li>
 *         <li>same hash, {@code COMPLETED} ⇒ {@link SendPixOutcome.Replayed} (the memoized response).</li>
 *         <li>same hash, {@code IN_PROGRESS} and fresh ⇒ {@link RequestInProgressException} ({@code 409}
 *             + Retry-After).</li>
 *         <li>same hash, {@code IN_PROGRESS} but <b>stale</b> (claimed &gt; {@value #STALE_SECONDS}s ago,
 *             i.e. a crash left the claim orphaned) ⇒ conditionally re-claim and do the work, so a crash
 *             never blocks the client until the 24h TTL.</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <p>Everything that is a <i>decision</i> lives here rather than in the controller (ADR-0011): amount
 * parsing (which enforces the strictly-positive money rule), id generation, reading the injected
 * {@link Clock}, and the whole idempotency verdict. The controller only binds the request (including the
 * raw header) and renders the outcome.
 */
public class SendPixUseCase {

    private static final Logger log = LoggerFactory.getLogger(SendPixUseCase.class);

    /**
     * An {@code IN_PROGRESS} claim older than this is treated as crash-orphaned and re-claimable — far
     * beyond any legitimate in-flight send, well below the 24h replay TTL (ADR-0002).
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
    private final LedgerClient ledger;
    private final EndToEndIdGenerator endToEndIds;
    private final Clock clock;

    public SendPixUseCase(
            TransactionRepository transactions,
            IdempotencyRepository idempotency,
            PixKeyResolver pixKeys,
            AccountLimitClient accountLimits,
            DailyLimitReservation dailyLimits,
            LedgerClient ledger,
            EndToEndIdGenerator endToEndIds,
            Clock clock) {
        this.transactions = transactions;
        this.idempotency = idempotency;
        this.pixKeys = pixKeys;
        this.accountLimits = accountLimits;
        this.dailyLimits = dailyLimits;
        this.ledger = ledger;
        this.endToEndIds = endToEndIds;
        this.clock = clock;
    }

    /**
     * Accept the send idempotently and return whether the transaction was freshly accepted or the
     * response was replayed.
     *
     * @throws com.platinumcoin.pix.payment.domain.InvalidAmountException the amount is not strictly
     *                                                                    positive money
     * @throws IdempotencyKeyRequiredException the {@code Idempotency-Key} was absent/blank
     * @throws IdempotencyKeyReuseException    the key was replayed with a different payload
     * @throws RequestInProgressException      a concurrent request with the same key is in flight
     * @throws com.platinumcoin.pix.payment.domain.KeyNotFoundException      the destination key does not
     *                                                                       resolve to an internal account
     * @throws LimitExceededException          the send would breach the debtor's daily Pix limit
     * @throws InsufficientFundsException      the ledger refused the debit for lack of funds
     * @throws com.platinumcoin.pix.payment.domain.LedgerUnavailableException the ledger was unreachable
     */
    public SendPixOutcome execute(SendPixCommand command) {
        String key = command.idempotencyKey();
        if (key == null || key.isBlank()) {
            log.warn("Send refused, the Idempotency-Key header is missing on a money-moving POST, "
                    + "returning 400 | debtorAccountId={}", command.debtorAccountId());
            throw new IdempotencyKeyRequiredException();
        }

        // Validate money BEFORE any idempotency write: a malformed request is a client error that must
        // leave no record behind (each retry simply 400s again).
        long amountCents = Money.toCents(command.amount());
        String accountId = command.debtorAccountId();
        String requestHash = requestHashOf(command);

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            Instant now = clock.instant();
            if (idempotency.claim(accountId, key, requestHash, now)) {
                log.info("Idempotency key claimed, proceeding to accept the payment | "
                        + "debtorAccountId={} idempotencyKey={} attempt={}", accountId, key, attempt);
                return acceptAndComplete(command, amountCents, accountId, key, now);
            }

            // Lost the claim: a live record exists. Inspect it to replay, 409, or (if stale) re-claim.
            Optional<IdempotencyRecord> found = idempotency.get(accountId, key, now);
            if (found.isEmpty()) {
                // The record vanished between our claim-loss and this read (lazy TTL / a racing
                // reclaim). Retry the whole decision — rare, and bounded by MAX_ATTEMPTS.
                log.warn("Idempotency claim lost but record not readable, retrying the decision | "
                        + "debtorAccountId={} idempotencyKey={} attempt={}", accountId, key, attempt);
                continue;
            }
            IdempotencyRecord record = found.get();

            if (!record.requestHash().equals(requestHash)) {
                log.warn("Idempotency key reused with a different payload, returning 409 | "
                                + "debtorAccountId={} idempotencyKey={} storedHash={} attemptedHash={}",
                        accountId, key, record.requestHash(), requestHash);
                throw new IdempotencyKeyReuseException();
            }

            if (record.status() == IdempotencyStatus.COMPLETED) {
                Map<String, String> snapshot = record.responseSnapshot();
                log.info("Idempotency hit on a completed request, replaying the stored response | "
                                + "debtorAccountId={} idempotencyKey={} httpStatus={} transactionId={}",
                        accountId, key, record.httpStatus(), snapshot.get("transactionId"));
                return SendPixOutcome.replayed(
                        record.httpStatus(), snapshot.get("transactionId"), snapshot.get("endToEndId"));
            }

            // IN_PROGRESS.
            if (isStale(record.claimedAt(), now)) {
                if (idempotency.reclaim(accountId, key, requestHash, record.claimedAt(), now)) {
                    log.warn("Stale in-progress idempotency claim re-claimed after a crash window, "
                                    + "proceeding to accept | debtorAccountId={} idempotencyKey={} "
                                    + "claimedAt={} staleSeconds={}",
                            accountId, key, record.claimedAt(), STALE_SECONDS);
                    return acceptAndComplete(command, amountCents, accountId, key, now);
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
     * Do the acceptance work, memoize the response, and return the fresh outcome. This is the
     * money-moving core, run only inside a won idempotency claim so a double-tap or replay never
     * repeats it. The orchestration order is the one the external flow (Sprint 6) will extend —
     * <b>resolve → (limit → fraud, soon) → debit → persist</b> — so an unknown destination is refused
     * before any counter is touched and the debit is the last thing that can fail before we memoize.
     */
    private SendPixOutcome acceptAndComplete(
            SendPixCommand command, long amountCents, String accountId, String key, Instant now) {
        // 1) Resolve the destination FIRST. An unknown key is a 422 before the limit counter is touched
        //    or any money moves — so a KEY_NOT_FOUND leaves no reservation to unwind.
        String creditorAccountId = pixKeys.resolveInternalCreditor(command.pixKey());
        log.info("Destination Pix key resolved to an internal creditor | creditorKey={} "
                        + "creditorAccountId={} debtorAccountId={}",
                command.pixKey(), creditorAccountId, accountId);

        // 2) Reserve the daily-limit headroom BEFORE the debit (step 20). Inside the won-claim path on
        //    purpose: the reservation is a non-idempotent counter increment, so it must run exactly once
        //    per idempotency key — a double-tap or a replay must never reserve twice. A DENY throws here.
        reserveDailyLimit(accountId, amountCents, now);

        // 3) Move the money and persist the terminal state. An internal transfer settles the instant the
        //    atomic ledger posting commits — there is no SPI leg — so this lands the transaction at
        //    SETTLED directly. INSUFFICIENT_FUNDS releases the reservation from step 2.
        Transaction transaction = settleInternally(command, creditorAccountId, amountCents, accountId, now);

        Map<String, String> snapshot = new LinkedHashMap<>();
        snapshot.put("transactionId", transaction.txId());
        snapshot.put("endToEndId", transaction.endToEndId());
        idempotency.complete(accountId, key, ACCEPTED_HTTP_STATUS, snapshot, clock.instant());

        log.info("Idempotency record completed and memoized for replay | debtorAccountId={} "
                        + "idempotencyKey={} transactionId={} httpStatus={}",
                accountId, key, transaction.txId(), ACCEPTED_HTTP_STATUS);
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
        throw new LimitExceededException();
    }

    /**
     * Mint the ids, command the atomic ledger debit/credit, and persist the settled transaction. For an
     * internal transfer the single {@code TransactWriteItems} <i>is</i> the settlement, so the terminal
     * state is {@code SETTLED} with {@code settledAt} stamped at the same instant the money moved
     * (Domain Safety Rule #4). The ledger is keyed by {@code txId} (Domain Safety Rule #2), so a retry
     * after a crash replays the committed posting rather than double-debiting.
     *
     * <p>On {@link InsufficientFundsException} the ledger wrote nothing — the guard lives inside its
     * transaction — so the daily-limit reservation taken in step 2 is <b>released</b> before the failure
     * propagates as a {@code 422}. A {@link com.platinumcoin.pix.payment.domain.LedgerUnavailableException}
     * is <i>not</i> released: nothing was debited and the client retries the same idempotency key, which
     * re-drives this whole path (the reservation is honoured by that retry). Leaving the record
     * {@code IN_PROGRESS} accepts the conservative over-count edge ADR-0007/step 20 already documents —
     * never overspend, self-heals next calendar day.
     */
    private Transaction settleInternally(
            SendPixCommand command, String creditorAccountId, long amountCents, String accountId,
            Instant now) {
        String txId = "tx-" + UUID.randomUUID();
        String endToEndId = endToEndIds.generate(now);
        String description = command.description() == null ? "" : command.description();

        log.info("Commanding the ledger to debit the payer and credit the payee atomically | txId={} "
                        + "endToEndId={} debtorAccountId={} creditorAccountId={} amountCents={}",
                txId, endToEndId, accountId, creditorAccountId, amountCents);
        try {
            ledger.postInternalTransfer(txId, accountId, creditorAccountId, amountCents, description);
        } catch (InsufficientFundsException e) {
            LocalDate day = now.atZone(LIMIT_ZONE).toLocalDate();
            dailyLimits.release(accountId, amountCents, day);
            log.warn("Ledger refused the debit for insufficient funds, released the daily-limit "
                            + "reservation, returning 422 | txId={} debtorAccountId={} amountCents={} day={}",
                    txId, accountId, amountCents, day);
            throw e;
        }

        // Internal transfer: the posting committed, so it is settled now.
        Transaction transaction = new Transaction(
                txId,
                endToEndId,
                accountId,
                command.pixKey(),
                creditorAccountId,
                amountCents,
                TransactionStatus.SETTLED,
                description,
                now,
                now);
        transactions.create(transaction);

        log.info("Internal Pix moved money and settled, persisted as SETTLED, returning 202 Accepted | "
                        + "txId={} status={} settledAt={}",
                txId, transaction.status(), transaction.settledAt());
        return transaction;
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
