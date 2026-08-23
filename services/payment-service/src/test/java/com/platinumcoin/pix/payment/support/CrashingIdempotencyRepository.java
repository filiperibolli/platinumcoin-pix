package com.platinumcoin.pix.payment.support;

import com.platinumcoin.pix.payment.domain.model.IdempotencyRecord;
import com.platinumcoin.pix.payment.domain.model.IdempotencyStatus;
import com.platinumcoin.pix.payment.domain.port.IdempotencyRepository;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * A decorator over the <b>real</b> {@code DynamoIdempotencyRepository} that can die at a chosen instant
 * (step 69). Every call it does not kill is delegated unchanged, so the claim, the phases and the memo
 * are genuine conditional writes against LocalStack — which is the point: what a recovery scenario
 * asserts is what survived in the table, and a fake table survives nothing worth asserting.
 *
 * <p>Two of the four kill points live here, and both are on the {@code POSTED} phase write specifically:
 * that is the write which happens immediately after the money moves, so it is the write whose presence
 * or absence defines the two halves of the crash window. The {@code RECORDED} phase write is deliberately
 * never a kill point — the interesting state after it is already covered by
 * {@link CrashPoint#AFTER_TRANSACTION_WRITE} and {@link CrashPoint#BEFORE_COMPLETE} on either side.
 */
public class CrashingIdempotencyRepository implements IdempotencyRepository {

    private final IdempotencyRepository delegate;
    private final CrashInjector crash;

    public CrashingIdempotencyRepository(IdempotencyRepository delegate, CrashInjector crash) {
        this.delegate = delegate;
        this.crash = crash;
    }

    @Override
    public boolean claim(String accountId, String key, String requestHash, String txId,
            String endToEndId, Instant now) {
        return delegate.claim(accountId, key, requestHash, txId, endToEndId, now);
    }

    @Override
    public Optional<IdempotencyRecord> get(String accountId, String key) {
        return delegate.get(accountId, key);
    }

    @Override
    public void advancePhase(String accountId, String key, IdempotencyStatus phase, Instant now) {
        if (phase == IdempotencyStatus.POSTED) {
            crash.crashIfArmedAt(CrashPoint.BEFORE_PHASE_POSTED);
        }
        delegate.advancePhase(accountId, key, phase, now);
        if (phase == IdempotencyStatus.POSTED) {
            crash.crashIfArmedAt(CrashPoint.AFTER_PHASE_POSTED);
        }
    }

    @Override
    public void complete(String accountId, String key, int httpStatus,
            Map<String, String> responseSnapshot, Instant now) {
        crash.crashIfArmedAt(CrashPoint.BEFORE_COMPLETE);
        delegate.complete(accountId, key, httpStatus, responseSnapshot, now);
    }

    @Override
    public boolean reclaim(String accountId, String key, String newRequestHash, Instant priorClaimedAt,
            Instant now) {
        return delegate.reclaim(accountId, key, newRequestHash, priorClaimedAt, now);
    }
}
