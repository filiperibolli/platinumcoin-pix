package com.platinumcoin.pix.payment.domain.usecase;

import com.platinumcoin.pix.payment.domain.model.IdempotencyRecord;
import com.platinumcoin.pix.payment.domain.model.IdempotencyStatus;
import com.platinumcoin.pix.payment.domain.port.IdempotencyRepository;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link IdempotencyRepository} for the plain-Java use-case tests — the same claim/replay/
 * reclaim semantics as the DynamoDB adapter, without a running store. It keeps the whole record
 * (including {@code expiresAt}) so the lazy-TTL and stale-claim branches can be driven by pinning the
 * clock. Not the concurrency proof — that is {@code IdempotencyIT} on real LocalStack; this fake is
 * intentionally single-threaded-simple.
 */
final class FakeIdempotencyRepository implements IdempotencyRepository {

    private record Stored(IdempotencyRecord record, long expiresAtEpoch) {
    }

    private final Map<String, Stored> byKey = new ConcurrentHashMap<>();

    @Override
    public boolean claim(String accountId, String key, String requestHash, Instant now) {
        String pk = pk(accountId, key);
        Stored existing = byKey.get(pk);
        if (existing != null && existing.expiresAtEpoch() >= now.getEpochSecond()) {
            return false; // a live record exists
        }
        byKey.put(pk, new Stored(
                new IdempotencyRecord(requestHash, IdempotencyStatus.IN_PROGRESS, now, 0, null),
                now.plusSeconds(24 * 3600).getEpochSecond()));
        return true;
    }

    @Override
    public Optional<IdempotencyRecord> get(String accountId, String key, Instant now) {
        Stored stored = byKey.get(pk(accountId, key));
        if (stored == null || stored.expiresAtEpoch() < now.getEpochSecond()) {
            return Optional.empty();
        }
        return Optional.of(stored.record());
    }

    @Override
    public void complete(
            String accountId, String key, int httpStatus, Map<String, String> responseSnapshot, Instant now) {
        String pk = pk(accountId, key);
        Stored stored = byKey.get(pk);
        IdempotencyRecord prior = stored.record();
        byKey.put(pk, new Stored(
                new IdempotencyRecord(prior.requestHash(), IdempotencyStatus.COMPLETED, prior.claimedAt(),
                        httpStatus, responseSnapshot),
                stored.expiresAtEpoch()));
    }

    @Override
    public boolean reclaim(
            String accountId, String key, String newRequestHash, Instant priorClaimedAt, Instant now) {
        String pk = pk(accountId, key);
        Stored stored = byKey.get(pk);
        if (stored == null
                || stored.record().status() != IdempotencyStatus.IN_PROGRESS
                || !stored.record().claimedAt().equals(priorClaimedAt)) {
            return false;
        }
        byKey.put(pk, new Stored(
                new IdempotencyRecord(newRequestHash, IdempotencyStatus.IN_PROGRESS, now, 0, null),
                now.plusSeconds(24 * 3600).getEpochSecond()));
        return true;
    }

    /** Directly plant a record (e.g. an aged IN_PROGRESS claim) to drive a specific branch. */
    void plant(String accountId, String key, IdempotencyRecord record, Instant now) {
        byKey.put(pk(accountId, key),
                new Stored(record, now.plusSeconds(24 * 3600).getEpochSecond()));
    }

    private static String pk(String accountId, String key) {
        return accountId + "#" + key;
    }
}
