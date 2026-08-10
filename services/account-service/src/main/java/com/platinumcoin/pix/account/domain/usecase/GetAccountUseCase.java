package com.platinumcoin.pix.account.domain.usecase;

import com.platinumcoin.pix.account.domain.exception.AccountNotFoundException;
import com.platinumcoin.pix.account.domain.model.Account;
import com.platinumcoin.pix.account.domain.port.AccountRepository;
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
        log.info("Internal service-to-service lookup of an account by id | accountId={}", accountId);
        Account account = accounts.findByAccountId(accountId)
                .orElseThrow(() -> {
                    // A caller asked for an id that doesn't exist — an ordinary lookup miss, not an
                    // actionable failure of this service, so INFO keeps the trace complete.
                    log.info("Internal lookup found no account with this id, returning 404 "
                            + "| accountId={}", accountId);
                    return new AccountNotFoundException("No account found for id " + accountId + ".");
                });
        log.info("Internal lookup resolved the account "
                        + "| accountId={} userId={} status={} dailyLimitCents={} createdAt={}",
                account.accountId(), account.userId(), account.status(), account.dailyLimitCents(),
                account.createdAt());
        return account;
    }
}
