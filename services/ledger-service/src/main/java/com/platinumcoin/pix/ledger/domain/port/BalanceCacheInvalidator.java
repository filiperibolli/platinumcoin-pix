package com.platinumcoin.pix.ledger.domain.port;

import java.util.Collection;

/**
 * Outbound port for telling the balance cache that an account's balance is no longer what it says
 * (step 40, ADR-0008). The ledger is the only writer of {@code pix_ledger} (ADR-0006), so it is the
 * only component that can know the instant a cached balance became a lie — which is why
 * <b>invalidation lives here</b> and not in the service that reads the cache.
 *
 * <p><b>This port never reads and never writes a balance.</b> It only deletes keys. That asymmetry is
 * deliberate: nothing about the cache may ever become an input to a money decision (ADR-0008's
 * correctness rule), and a port that cannot answer "what is the balance?" cannot be misused to answer
 * it. The {@code balanceCents >= :amount} guard lives inside the {@code TransactWriteItems} (step 14,
 * Domain Safety Rule #3) and reads DynamoDB, never Redis.
 *
 * <p><b>Best-effort by contract.</b> The caller invokes this <i>after</i> the posting has committed,
 * and treats any failure as a degradation rather than an error: the short TTL on the cached entry is
 * the backstop that bounds how long a missed eviction can be visible. An implementation is therefore
 * free to throw — {@link com.platinumcoin.pix.ledger.domain.usecase.PostDoubleEntryUseCase} swallows
 * and logs it — but must never block the money path waiting on the cache.
 */
public interface BalanceCacheInvalidator {

    /**
     * Drop the cached balances of the given accounts. Takes a collection rather than one id per call
     * so a posting's two legs are one round-trip: an all-or-nothing single delete is both cheaper and
     * easier to reason about than two independent evictions that could half-succeed.
     *
     * @param accountIds the accounts whose balance just changed (a posting's debit and credit legs)
     */
    void evict(Collection<String> accountIds);
}
