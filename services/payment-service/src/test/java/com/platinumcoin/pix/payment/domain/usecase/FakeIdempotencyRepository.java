package com.platinumcoin.pix.payment.domain.usecase;

import com.platinumcoin.pix.payment.domain.model.IdempotencyRecord;
import com.platinumcoin.pix.payment.domain.model.IdempotencyStatus;
import com.platinumcoin.pix.payment.domain.port.IdempotencyRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link IdempotencyRepository} for the plain-Java use-case tests — the same claim/replay/
 * reclaim semantics as the DynamoDB adapter, without a running store. It keeps the whole record
 * (including {@code expiresAt} and the operation identity the claim persists, ADR-0014) so the
 * lazy-TTL, stale-claim and crash-resume branches can be driven by pinning the clock. Not the
 * concurrency proof — that is {@code IdempotencyIT} on real LocalStack; this fake is intentionally
 * single-threaded-simple.
 */
final class FakeIdempotencyRepository implements IdempotencyRepository {

    /** Everything a claim call was handed — so a test can assert on the write itself, not its effect. */
    record ClaimCall(String accountId, String key, String requestHash, String txId, String endToEndId) {
    }

    private static final long TTL_SECONDS = 24 * 3600;

    private final Map<String, IdempotencyRecord> byKey = new ConcurrentHashMap<>();
    private final List<ClaimCall> claims = new ArrayList<>();

    @Override
    public boolean claim(
            String accountId, String key, String requestHash, String txId, String endToEndId,
            Instant now) {
        claims.add(new ClaimCall(accountId, key, requestHash, txId, endToEndId));
        String pk = pk(accountId, key);
        IdempotencyRecord existing = byKey.get(pk);
        // Mirrors the adapter's condition: only an absent record, or an expired AND terminal one, may
        // be overwritten. An expired non-terminal record is a stranded money operation and blocks.
        boolean blocked = existing != null && !(existing.expired(now) && existing.status().terminal());
        if (blocked) {
            return false;
        }
        byKey.put(pk, new IdempotencyRecord(requestHash, txId, endToEndId, IdempotencyStatus.CLAIMED,
                now, now.plusSeconds(TTL_SECONDS), 0, null));
        return true;
    }

    @Override
    public Optional<IdempotencyRecord> get(String accountId, String key) {
        // Expired records are returned, exactly like the adapter: the expiry verdict is the use case's.
        return Optional.ofNullable(byKey.get(pk(accountId, key)));
    }

    @Override
    public void advancePhase(String accountId, String key, IdempotencyStatus phase, Instant now) {
        String pk = pk(accountId, key);
        IdempotencyRecord prior = byKey.get(pk);
        if (prior == null || prior.status().terminal()) {
            return; // advisory, and never moves a completed record backwards
        }
        byKey.put(pk, new IdempotencyRecord(prior.requestHash(), prior.txId(), prior.endToEndId(),
                phase, prior.claimedAt(), prior.expiresAt(), prior.httpStatus(),
                prior.responseSnapshot()));
    }

    @Override
    public void complete(
            String accountId, String key, int httpStatus, Map<String, String> responseSnapshot, Instant now) {
        String pk = pk(accountId, key);
        IdempotencyRecord prior = byKey.get(pk);
        byKey.put(pk, new IdempotencyRecord(prior.requestHash(), prior.txId(), prior.endToEndId(),
                IdempotencyStatus.COMPLETED, prior.claimedAt(), prior.expiresAt(), httpStatus,
                responseSnapshot));
    }

    @Override
    public boolean reclaim(
            String accountId, String key, String newRequestHash, Instant priorClaimedAt, Instant now) {
        String pk = pk(accountId, key);
        IdempotencyRecord prior = byKey.get(pk);
        if (prior == null
                || prior.status().terminal()
                || !prior.hasIdentity()
                || !prior.claimedAt().equals(priorClaimedAt)) {
            return false;
        }
        // The identity is carried over untouched — the adapter's SET clause cannot express otherwise.
        byKey.put(pk, new IdempotencyRecord(newRequestHash, prior.txId(), prior.endToEndId(),
                IdempotencyStatus.CLAIMED, now, now.plusSeconds(TTL_SECONDS), 0, null));
        return true;
    }

    /** Directly plant a record (e.g. an aged claim, or one with no identity) to drive a branch. */
    void plant(String accountId, String key, IdempotencyRecord record) {
        byKey.put(pk(accountId, key), record);
    }

    /** Every {@code claim} call in order — how a test asserts what the claim <i>wrote</i>. */
    List<ClaimCall> claims() {
        return claims;
    }

    private static String pk(String accountId, String key) {
        return accountId + "#" + key;
    }
}
