package com.platinumcoin.pix.fraud.domain.port;

/**
 * Outbound port for the pre-computed fraud features the in-path check reads. Its only implementation is
 * the Redis adapter in {@code infra/persistence/} — the domain declares this interface, {@code infra/}
 * fulfils it (ADR-0010). Keeping the store behind a port is what lets {@link
 * com.platinumcoin.pix.fraud.domain.usecase.ScoreFraudUseCase} be unit-tested against an in-memory fake
 * with no Redis at all.
 *
 * <p><b>Every method both records the current request and returns the resulting feature</b> — the
 * "increment-then-decide, this transfer counts itself" semantics chosen for step 24. The N-th transfer
 * of a burst therefore sees {@code count == N}. The trade-off: scoring is <i>not</i> idempotent, so a
 * retried {@code /score} double-counts velocity; acceptable because velocity is a soft signal and the
 * caller fails open (ADR-0005), revisited in step 25.
 *
 * <p>Each method is a single Redis round-trip (an {@code INCR}/{@code INCRBY} that also arms the window
 * TTL, or one {@code SADD}); no read-then-write races, and the whole port call budget is a few
 * sub-millisecond ops well inside the 150ms target.
 */
public interface FraudSignalStore {

    /**
     * Count this transfer into the account's short (per-minute) velocity window and return the new
     * running count. Arms the window's expiry on the first increment.
     */
    long recordAndCountRecent(String accountId);

    /**
     * Add {@code amountCents} to the account's long (per-hour) rolling money total and return the new
     * running sum in cents. Arms the window's expiry on the first increment.
     */
    long recordAndSumRecentAmount(String accountId, long amountCents);

    /**
     * Record that {@code accountId} has now paid {@code pixKey}, returning {@code true} iff this is the
     * <i>first</i> time (the payee was previously unseen). Backed by a persistent per-account set, so
     * "new" means "never paid before", not "not paid recently".
     */
    boolean recordPayeeReturningIsNew(String accountId, String pixKey);
}
