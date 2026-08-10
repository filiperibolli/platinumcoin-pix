package com.platinumcoin.pix.payment.domain.model;

import java.time.Instant;
import java.util.Map;

/**
 * An idempotency record as the domain sees it — the {@code IDEM#<accountId>#<key> / META} item of
 * {@code pix_idempotency} (docs/data-model.md §5), minus the storage detail. It is the memory that
 * makes a retried {@code POST /payments/pix} safe: the {@code requestHash} decides replay-vs-{@code 409},
 * the {@code status} decides replay-vs-in-progress, and {@code claimedAt} decides whether an
 * {@code IN_PROGRESS} claim is a live request or a crash-orphaned one to be re-claimed.
 *
 * <p>The {@code responseSnapshot} is a small {@code Map} of plain strings (the identifying fields of
 * the accepted payment: {@code transactionId}, {@code endToEndId}), NOT a serialized HTTP body — the
 * wire vocabulary ({@code "PROCESSING"}) is re-applied in {@code api/} when the snapshot is replayed,
 * so no wire concern leaks into {@code domain/}. It is {@code null} while the record is
 * {@code IN_PROGRESS} (nothing has been produced to replay yet).
 *
 * @param requestHash      canonical-JSON SHA-256 of the original request fields
 * @param status           {@link IdempotencyStatus#IN_PROGRESS} or {@link IdempotencyStatus#COMPLETED}
 * @param claimedAt        when the key was (re-)claimed — the staleness clock for orphan recovery
 * @param httpStatus       the memoized HTTP status to replay (0 while in progress)
 * @param responseSnapshot the memoized response fields to replay ({@code null} while in progress)
 */
public record IdempotencyRecord(
        String requestHash,
        IdempotencyStatus status,
        Instant claimedAt,
        int httpStatus,
        Map<String, String> responseSnapshot) {
}
