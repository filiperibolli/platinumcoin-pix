package com.platinumcoin.pix.payment.domain.usecase;

import com.platinumcoin.pix.payment.domain.model.AccountBalance;
import com.platinumcoin.pix.payment.domain.port.BalanceCache;
import com.platinumcoin.pix.payment.domain.port.LedgerClient;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Read the caller's balance through the cache (step 40, ADR-0008) — the platform's highest-volume
 * operation, and the one that must hold {@code p99 < 300ms}.
 *
 * <p><b>Cache-aside, in four lines:</b> hit ⇒ return it; miss ⇒ read the ledger, populate, return.
 * The write path is somebody else's job: ledger-service deletes the affected keys after a posting
 * commits. That split is what makes the pattern <i>cache-aside</i> rather than read-through — the
 * cache is a thing this use case uses, not a layer it hides behind — and it is what lets the two
 * services keep entirely different Redis rights (this one may read and write one key; the ledger may
 * only delete).
 *
 * <p><b>What this use case may never become.</b> Nothing here decides whether money may move. A value
 * from this method can be up to one TTL old, and the platform's guard against an overdraft is a
 * condition expression evaluated inside the ledger's transaction (Domain Safety Rule #3). If a future
 * step ever wants to "check the balance first" before a send, the answer is no — that is a
 * read-then-check race, stale cache or not.
 *
 * <p><b>The clock is here</b> (ADR-0011): {@code asOf} is stamped once, at the moment the ledger
 * answered, and then travels with the value through the cache. A hit returns the original instant, so
 * the age of the number never quietly resets to zero on its way to the client.
 */
public class GetBalanceUseCase {

    private static final Logger log = LoggerFactory.getLogger(GetBalanceUseCase.class);

    private final BalanceCache cache;
    private final LedgerClient ledger;
    private final Clock clock;

    public GetBalanceUseCase(BalanceCache cache, LedgerClient ledger, Clock clock) {
        this.cache = cache;
        this.ledger = ledger;
        this.clock = clock;
    }

    public AccountBalance execute(String accountId) {
        log.info("Balance read requested by the account's owner | accountId={}", accountId);

        var cached = cache.get(accountId);
        if (cached.isPresent()) {
            AccountBalance balance = cached.get();
            log.info("Balance served from the cache without reading the ledger | accountId={} "
                            + "balanceCents={} asOf={}",
                    accountId, balance.balanceCents(), balance.asOf());
            return balance;
        }

        long balanceCents = ledger.readBalanceCents(accountId);
        // Milliseconds, matching the resolution the ledger stamps its own postings with: an asOf that
        // claimed nanoseconds would be precision this value does not have.
        Instant asOf = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        AccountBalance balance = new AccountBalance(accountId, balanceCents, asOf);

        // Populate AFTER the read succeeded, and only then: a failed ledger read caches nothing, so a
        // blip never becomes a TTL-long lie, and a non-existent account is never memoized as one.
        cache.put(balance);
        log.info("Balance read from the ledger on a cache miss and cached for subsequent reads | "
                        + "accountId={} balanceCents={} asOf={}",
                accountId, balanceCents, asOf);
        return balance;
    }
}
