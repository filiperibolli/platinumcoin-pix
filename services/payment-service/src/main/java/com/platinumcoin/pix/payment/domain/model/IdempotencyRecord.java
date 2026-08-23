package com.platinumcoin.pix.payment.domain.model;

import java.time.Instant;
import java.util.Map;

/**
 * An idempotency record as the domain sees it — the {@code IDEM#<accountId>#<key> / META} item of
 * {@code pix_idempotency} (docs/data-model.md §5), minus the storage detail. It is the memory that
 * makes a retried {@code POST /payments/pix} safe: the {@code requestHash} decides replay-vs-{@code 409},
 * the {@code status} decides replay-vs-in-progress, {@code claimedAt} decides whether a non-terminal
 * claim is a live request or a crash-orphaned one to be re-claimed — and, since ADR-0014, the
 * {@code txId}/{@code endToEndId} say <b>what the money of this operation is called</b>, so a resume
 * re-posts under the identity the ledger may already hold instead of minting a second one.
 *
 * <p>The {@code responseSnapshot} is a small {@code Map} of plain strings (the identifying fields of
 * the accepted payment: {@code transactionId}, {@code endToEndId}), NOT a serialized HTTP body — the
 * wire vocabulary ({@code "PROCESSING"}) is re-applied in {@code api/} when the snapshot is replayed,
 * so no wire concern leaks into {@code domain/}. It is {@code null} until the record is
 * {@code COMPLETED} (nothing has been produced to replay yet).
 *
 * <p><b>Expiry is reported, not applied, by the adapter</b> (ADR-0014): {@code expiresAt} travels with
 * the record so the use case — which owns policy and the clock (ADR-0011) — can tell a legitimately
 * reusable key from a stranded money operation, two cases the adapter cannot distinguish.
 *
 * @param requestHash      canonical-JSON SHA-256 of the original request fields
 * @param txId             the ledger identity every monetary effect of this operation carries
 *                         ({@code null} only on a record written before ADR-0014)
 * @param endToEndId       the rail identity BACEN dedupes on ({@code null} on a pre-ADR-0014 record)
 * @param status           the phase, {@code CLAIMED → POSTED → RECORDED → COMPLETED}
 * @param claimedAt        when the key was (re-)claimed — the staleness clock for orphan recovery
 * @param expiresAt        the end of the 24h replay window (DynamoDB TTL deletion is lazy, so this is
 *                         checked in code, never assumed to have been enforced by a delete)
 * @param httpStatus       the memoized HTTP status to replay (0 until completed)
 * @param responseSnapshot the memoized response fields to replay ({@code null} until completed)
 */
public record IdempotencyRecord(
        String requestHash,
        String txId,
        String endToEndId,
        IdempotencyStatus status,
        Instant claimedAt,
        Instant expiresAt,
        int httpStatus,
        Map<String, String> responseSnapshot) {

    /**
     * Is the 24h replay window closed? Compared in <b>whole epoch seconds</b> on purpose: this is the
     * same arithmetic as the adapter's {@code expiresAt < :now} condition, and the use case's verdict
     * must never disagree with the conditional write that enforces it.
     */
    public boolean expired(Instant now) {
        return expiresAt.getEpochSecond() < now.getEpochSecond();
    }

    /**
     * Does this record name the money it is responsible for? A record written before ADR-0014 does not,
     * and must therefore be refused rather than resumed — guessing an identity is precisely the defect
     * that ADR removes.
     */
    public boolean hasIdentity() {
        return txId != null && !txId.isBlank() && endToEndId != null && !endToEndId.isBlank();
    }
}
