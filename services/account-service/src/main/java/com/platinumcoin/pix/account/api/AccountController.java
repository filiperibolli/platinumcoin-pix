package com.platinumcoin.pix.account.api;

import com.platinumcoin.pix.account.domain.Account;
import com.platinumcoin.pix.account.domain.AccountRepository;
import com.platinumcoin.pix.common.error.DomainException;
import com.platinumcoin.pix.common.security.AuthenticatedUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inbound adapter for {@code GET /v1/accounts/me}: returns the authenticated caller's own account.
 * The account is derived <b>only</b> from the JWT — the {@link AuthenticatedUser} (userId +
 * accountId) is injected by common-lib, and there is no path/query/body parameter that could name a
 * different account. This is the same "identity comes from the token" principle that later protects
 * the debited account in the send flow (Domain Safety Rule #1).
 *
 * <p>The repository returns {@link java.util.Optional}; the controller (not the domain) turns "empty"
 * into a {@code 404 ACCOUNT_NOT_FOUND} problem+json, keeping HTTP concerns out of the domain.
 */
@RestController
@RequestMapping("/v1/accounts")
public class AccountController {

    private static final Logger log = LoggerFactory.getLogger(AccountController.class);

    private final AccountRepository accounts;

    public AccountController(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @GetMapping("/me")
    public AccountResponse me(AuthenticatedUser user) {
        log.info("account.me.lookup userId={} accountId={}", user.userId(), user.accountId());
        Account account = accounts.findByUser(user.userId(), user.accountId())
                .orElseThrow(() -> {
                    // Valid token but no matching account row — a genuine degradation, not a client
                    // error: the JWT claimed an account this service can't find. WARN so it surfaces.
                    log.warn("account.me.missing userId={} accountId={}", user.userId(), user.accountId());
                    return new DomainException("ACCOUNT_NOT_FOUND", HttpStatus.NOT_FOUND,
                            "No account found for the authenticated user.");
                });
        log.info("account.me.resolved accountId={} status={} dailyLimitCents={}",
                account.accountId(), account.status(), account.dailyLimitCents());
        return AccountResponse.from(account);
    }
}
