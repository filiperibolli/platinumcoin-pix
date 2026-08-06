package com.platinumcoin.pix.payment.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Outbound port for the API-layer idempotency store (owner of {@code pix_idempotency}, ADR-0002). The
 * domain declares the four operations of a record's lifecycle; {@code infra/} implements them against
 * DynamoDB, so no AWS type reaches the use case (ADR-0010, enforced by {@code PaymentArchitectureTest}).
 *
 * <p>Every method is scoped by {@code (accountId, key)} — the composite the store keys on
 * ({@code IDEM#<accountId>#<key>}) — because an idempotency key is only unique <i>within</i> an account
 * (ADR-0002: two users may coincidentally pick the same UUID).
 */
public interface IdempotencyRepository {

    /**
     * Atomically claim the key for a first attempt: a conditional put of an {@code IN_PROGRESS} record
     * stamped with {@code requestHash} and {@code claimedAt = now}, succeeding only if no <b>live</b>
     * record exists (absent, or present but past its {@code expiresAt} — DynamoDB TTL deletion is lazy,
     * so an expired record must not block a fresh claim for hours).
     *
     * @return {@code true} if this caller won the claim and must now do the work; {@code false} if a
     *         live record already exists and the caller must inspect it via {@link #get}
     */
    boolean claim(String accountId, String key, String requestHash, Instant now);

    /**
     * Read the live record for the key, or {@link Optional#empty()} if absent <b>or expired</b> — an
     * item whose {@code expiresAt} is in the past is treated as absent (lazy TTL, ADR-0002), so the
     * 24h replay window is enforced by the application, not by DynamoDB's deletion lag.
     */
    Optional<IdempotencyRecord> get(String accountId, String key, Instant now);

    /**
     * Memoize the outcome: move the record to {@code COMPLETED}, storing the HTTP status and the
     * response snapshot to replay on any later retry.
     */
    void complete(String accountId, String key, int httpStatus, Map<String, String> responseSnapshot, Instant now);

    /**
     * Re-claim a stale ({@code IN_PROGRESS}, crash-orphaned) record for a retry: a conditional update
     * that stamps a fresh {@code claimedAt} and the new {@code requestHash}, succeeding only if the
     * record's {@code claimedAt} is still the observed {@code priorClaimedAt} — so exactly one racing
     * retry re-claims and the others see {@code IN_PROGRESS}.
     *
     * @return {@code true} if this caller re-claimed and must now do the work; {@code false} if another
     *         retry moved the record first
     */
    boolean reclaim(String accountId, String key, String newRequestHash, Instant priorClaimedAt, Instant now);
}
