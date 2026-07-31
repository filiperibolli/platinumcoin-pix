package com.platinumcoin.pix.account.api;

import com.platinumcoin.pix.account.domain.AccountRepository;
import com.platinumcoin.pix.common.error.DomainException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inbound adapter for the internal lookup {@code GET /internal/accounts/{accountId}} — how other
 * services read account config without sharing the {@code pix_accounts} table (ADR-0006). It is NOT
 * on the public allow-list, so it sits behind the shared {@code JwtAuthFilter} and requires a valid
 * token; but unlike {@code /me}, the account comes from the <b>path</b> (the caller is asking about
 * some other account by id), not from the token. It is therefore deliberately not account-scoped —
 * a purely internal seam. A deployed posture would gate it with a service credential/scope or mTLS
 * rather than an end-user token (tracked for step-45 hardening).
 */
@RestController
@RequestMapping("/internal/accounts")
public class InternalAccountController {

    private static final Logger log = LoggerFactory.getLogger(InternalAccountController.class);

    private final AccountRepository accounts;

    public InternalAccountController(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @GetMapping("/{accountId}")
    public InternalAccountResponse byId(@PathVariable("accountId") String accountId) {
        log.info("account.internal.lookup accountId={}", accountId);
        return accounts.findByAccountId(accountId)
                .map(InternalAccountResponse::from)
                .orElseThrow(() -> new DomainException("ACCOUNT_NOT_FOUND", HttpStatus.NOT_FOUND,
                        "No account found for id " + accountId + "."));
    }
}
