package com.platinumcoin.pix.account.domain.usecase;

import com.platinumcoin.pix.account.domain.Account;
import com.platinumcoin.pix.account.domain.AccountNotFoundException;
import com.platinumcoin.pix.account.domain.AccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Read any account by id — the service-to-service seam other services use instead of reading the
 * {@code pix_accounts} table themselves (ADR-0006). Unlike {@link GetMyAccountUseCase} this is
 * deliberately <b>not</b> account-scoped: the caller names the account, because it is asking about
 * someone else's (payment-service reading a payee's daily limit, for instance).
 */
public class GetAccountUseCase {

    private static final Logger log = LoggerFactory.getLogger(GetAccountUseCase.class);

    private final AccountRepository accounts;

    public GetAccountUseCase(AccountRepository accounts) {
        this.accounts = accounts;
    }

    public Account execute(String accountId) {
        log.info("account.internal.lookup accountId={}", accountId);
        Account account = accounts.findByAccountId(accountId)
                .orElseThrow(() -> {
                    // A caller asked for an id that doesn't exist — an ordinary lookup miss, not an
                    // actionable failure of this service, so INFO keeps the trace complete.
                    log.info("account.internal.miss accountId={}", accountId);
                    return new AccountNotFoundException("No account found for id " + accountId + ".");
                });
        log.info("account.internal.resolved accountId={} status={} dailyLimitCents={}",
                account.accountId(), account.status(), account.dailyLimitCents());
        return account;
    }
}
