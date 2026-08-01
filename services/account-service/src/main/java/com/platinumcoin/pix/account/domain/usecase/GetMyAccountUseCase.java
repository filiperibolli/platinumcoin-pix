package com.platinumcoin.pix.account.domain.usecase;

import com.platinumcoin.pix.account.domain.Account;
import com.platinumcoin.pix.account.domain.AccountNotFoundException;
import com.platinumcoin.pix.account.domain.AccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Read the caller's <b>own</b> account (ADR-0011). Both identifiers come from the validated JWT —
 * the {@code api/} edge has no path/query/body parameter that could name a different account, which
 * is the same "identity comes from the token" principle that later protects the debited account
 * (Domain Safety Rule #1).
 *
 * <p>Deliberately thin: it delegates to one port and turns "absent" into a domain exception. ADR-0011
 * accepts exactly this cost — a class like this exists so the service's capability list is readable
 * from {@code domain/usecase/}, and so a non-HTTP caller can reuse the lookup.
 */
public class GetMyAccountUseCase {

    private static final Logger log = LoggerFactory.getLogger(GetMyAccountUseCase.class);

    private final AccountRepository accounts;

    public GetMyAccountUseCase(AccountRepository accounts) {
        this.accounts = accounts;
    }

    public Account execute(String userId, String accountId) {
        log.info("Looking up the caller's own account, both ids taken from the JWT "
                + "| userId={} accountId={}", userId, accountId);
        Account account = accounts.findByUser(userId, accountId)
                .orElseThrow(() -> {
                    // Valid token but no matching account row — a genuine degradation, not a client
                    // error: the JWT claimed an account this service can't find. WARN so it surfaces.
                    log.warn("Valid token but no matching account row, returning 404 "
                            + "| userId={} accountId={}", userId, accountId);
                    return new AccountNotFoundException("No account found for the authenticated user.");
                });
        log.info("Resolved the caller's own account "
                        + "| accountId={} userId={} status={} dailyLimitCents={} createdAt={}",
                account.accountId(), account.userId(), account.status(), account.dailyLimitCents(),
                account.createdAt());
        return account;
    }
}
