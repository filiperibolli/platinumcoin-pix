package com.platinumcoin.pix.payment.domain.port;

import com.platinumcoin.pix.payment.domain.model.IdempotencyRecord;
import com.platinumcoin.pix.payment.domain.model.IdempotencyStatus;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Outbound port for the API-layer idempotency store (owner of {@code pix_idempotency}, ADR-0002,
 * amended by ADR-0014). The domain declares the operations of a record's lifecycle; {@code infra/}
 * implements them against DynamoDB, so no AWS type reaches the use case (ADR-0010, enforced by
 * {@code PaymentArchitectureTest}).
 *
 * <p>Every method is scoped by {@code (accountId, key)} — the composite the store keys on
 * ({@code IDEM#<accountId>#<key>}) — because an idempotency key is only unique <i>within</i> an account
 * (ADR-0002: two users may coincidentally pick the same UUID).
 */
public interface IdempotencyRepository {

    /**
     * Atomically claim the key for a first attempt <b>and persist the operation's identity in the same
     * write</b>: a conditional put of a {@link IdempotencyStatus#CLAIMED} record stamped with
     * {@code requestHash}, {@code txId}, {@code endToEndId} and {@code claimedAt = now}.
     *
     * <p>One conditional write establishes two things at once (ADR-0014): "this request is mine to
     * execute", and "this is the name every monetary effect of it will carry". Because the identity is
     * written <i>before</i> any money can move and every effect is keyed on it, there is no instant at
     * which an accepted request has a monetary effect nobody can recognise on resume.
     *
     * <p>The claim succeeds only if no record exists, <b>or</b> the existing one is past its window
     * <i>and</i> terminal. An expired record still in a non-terminal status is deliberately <b>not</b>
     * overwritten: it is an unresolved money operation, and recycling its key would hand a client a
     * fresh identity for money that may already have moved.
     *
     * @return {@code true} if this caller won the claim and must now do the work; {@code false} if a
     *         record blocks it and the caller must inspect it via {@link #get}
     */
    boolean claim(
            String accountId, String key, String requestHash, String txId, String endToEndId, Instant now);

    /**
     * Read the record for the key, <b>including an expired one</b>, or {@link Optional#empty()} if the
     * item is absent.
     *
     * <p>The adapter no longer hides expired records (ADR-0014): "expired and terminal" (the key is
     * legitimately reusable) and "expired and unresolved" (a stranded money operation) are the same
     * item to storage but opposite verdicts to the business, so the decision — and the clock it needs —
     * belongs to the use case (ADR-0011). {@link IdempotencyRecord#expired(Instant)} is how it asks.
     */
    Optional<IdempotencyRecord> get(String accountId, String key);

    /**
     * Move the record to an intermediate phase ({@link IdempotencyStatus#POSTED},
     * {@link IdempotencyStatus#RECORDED}) as the operation progresses.
     *
     * <p><b>Advisory, and therefore best-effort</b> (ADR-0014 §3): the phase informs logs and recovery
     * decisions, and correctness rests on the {@code txId} and the ledger's guard instead. An
     * implementation must never let a failed phase advance break an operation whose money has already
     * moved — it reports the failure and returns normally. It must also never move a
     * {@link IdempotencyStatus#COMPLETED} record backwards.
     */
    void advancePhase(String accountId, String key, IdempotencyStatus phase, Instant now);

    /**
     * Memoize the outcome: move the record to {@link IdempotencyStatus#COMPLETED}, storing the HTTP
     * status and the response snapshot to replay on any later retry.
     */
    void complete(String accountId, String key, int httpStatus, Map<String, String> responseSnapshot, Instant now);

    /**
     * Re-claim a stale (non-terminal, crash-orphaned) record for a retry: a conditional update that
     * stamps a fresh {@code claimedAt} and the new {@code requestHash}, succeeding only if the record's
     * {@code claimedAt} is still the observed {@code priorClaimedAt} — so exactly one racing retry
     * re-claims and the others see in-progress.
     *
     * <p><b>It must not touch {@code txId} or {@code endToEndId}.</b> A re-claim that changed the
     * identity is exactly the double-debit ADR-0014 closes, so the prohibition is a property of the
     * update expression (which simply does not mention them), not a convention a future edit can
     * quietly break.
     *
     * @return {@code true} if this caller re-claimed and must now do the work under the <b>stored</b>
     *         identity; {@code false} if another retry moved the record first
     */
    boolean reclaim(String accountId, String key, String newRequestHash, Instant priorClaimedAt, Instant now);
}
