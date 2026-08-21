package com.platinumcoin.pix.payment.domain.port;

import com.platinumcoin.pix.payment.domain.model.AccountBalance;
import java.util.Optional;

/**
 * Outbound port for the balance cache (step 40, ADR-0008) — the read half of cache-aside. The domain
 * states the two operations the pattern needs; {@code infra/} implements them against Redis, the
 * local stand-in for ElastiCache.
 *
 * <p><b>Read-only for display, by contract.</b> Every value this port returns may be up to one TTL
 * old, so it is fit to <i>show</i> a customer and unfit to <i>decide</i> anything about money. The
 * platform's money decision — {@code balanceCents >= :amount} — is a condition expression inside the
 * ledger's {@code TransactWriteItems} (step 14, Domain Safety Rule #3) and reads DynamoDB. That is why
 * only {@link com.platinumcoin.pix.payment.domain.usecase.GetBalanceUseCase} depends on this port and
 * {@code SendPixUseCase} does not, and why {@code PaymentArchitectureTest} fails the build if that
 * ever changes: "the cache never feeds a money decision" is enforced by construction, not by review.
 *
 * <p><b>There is no {@code evict} here.</b> Invalidation belongs to the writer — ledger-service evicts
 * the affected keys after a posting commits (its {@code BalanceCacheInvalidator}) — because only the
 * writer knows the instant a cached balance became wrong. A reader that could evict would invite the
 * older, racier pattern of "delete it when I suspect it is stale".
 *
 * <p><b>Failure is a miss, never an error.</b> An implementation that cannot reach Redis returns
 * {@link Optional#empty()} from {@link #get} and does nothing on {@link #put}: losing the cache must
 * degrade latency, never availability (ADR-0008).
 */
public interface BalanceCache {

    /** The cached balance of the account, or empty on a miss — including when the cache is down. */
    Optional<AccountBalance> get(String accountId);

    /**
     * Store the balance under its account, with the adapter's configured TTL (5s). The TTL is the
     * adapter's because it is an infrastructure property of the cache, not a rule of the domain — and
     * it is the backstop that bounds staleness if the writer's eviction is ever lost.
     */
    void put(AccountBalance balance);
}
