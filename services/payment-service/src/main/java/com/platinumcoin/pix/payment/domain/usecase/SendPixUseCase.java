package com.platinumcoin.pix.payment.domain.usecase;

import com.platinumcoin.pix.common.idempotency.CanonicalJson;
import com.platinumcoin.pix.payment.domain.EndToEndIdGenerator;
import com.platinumcoin.pix.payment.domain.IdempotencyKeyRequiredException;
import com.platinumcoin.pix.payment.domain.IdempotencyKeyReuseException;
import com.platinumcoin.pix.payment.domain.IdempotencyRecord;
import com.platinumcoin.pix.payment.domain.IdempotencyRepository;
import com.platinumcoin.pix.payment.domain.IdempotencyStatus;
import com.platinumcoin.pix.payment.domain.Money;
import com.platinumcoin.pix.payment.domain.RequestInProgressException;
import com.platinumcoin.pix.payment.domain.Transaction;
import com.platinumcoin.pix.payment.domain.TransactionRepository;
import com.platinumcoin.pix.payment.domain.TransactionStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
 *   <li><b>claim</b> — a conditional put wins or loses atomically. Win ⇒ do the work (mint ids, persist
 *       {@code RECEIVED}), memoize the response, return {@link SendPixOutcome.Accepted}.</li>
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

    private final TransactionRepository transactions;
    private final IdempotencyRepository idempotency;
    private final EndToEndIdGenerator endToEndIds;
    private final Clock clock;

    public SendPixUseCase(
            TransactionRepository transactions,
            IdempotencyRepository idempotency,
            EndToEndIdGenerator endToEndIds,
            Clock clock) {
        this.transactions = transactions;
        this.idempotency = idempotency;
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

    /** Do the acceptance work, memoize the response, and return the fresh outcome. */
    private SendPixOutcome acceptAndComplete(
            SendPixCommand command, long amountCents, String accountId, String key, Instant now) {
        Transaction transaction = accept(command, amountCents, now);

        Map<String, String> snapshot = new LinkedHashMap<>();
        snapshot.put("transactionId", transaction.txId());
        snapshot.put("endToEndId", transaction.endToEndId());
        idempotency.complete(accountId, key, ACCEPTED_HTTP_STATUS, snapshot, clock.instant());

        log.info("Idempotency record completed and memoized for replay | debtorAccountId={} "
                        + "idempotencyKey={} transactionId={} httpStatus={}",
                accountId, key, transaction.txId(), ACCEPTED_HTTP_STATUS);
        return SendPixOutcome.accepted(transaction);
    }

    /** The acceptance itself: mint ids, stamp the clock, persist as {@code RECEIVED}. */
    private Transaction accept(SendPixCommand command, long amountCents, Instant now) {
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
