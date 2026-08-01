package com.platinumcoin.pix.account.api;

import com.platinumcoin.pix.account.domain.usecase.GetAccountUseCase;
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
 *
 * <p>Per ADR-0011: no port, no policy, no logging of business stages here — the use case owns all
 * three, and {@link AccountExceptionHandler} maps its "not found" to {@code 404 ACCOUNT_NOT_FOUND}.
 */
@RestController
@RequestMapping("/internal/accounts")
public class InternalAccountController {

    private final GetAccountUseCase getAccount;

    public InternalAccountController(GetAccountUseCase getAccount) {
        this.getAccount = getAccount;
    }

    @GetMapping("/{accountId}")
    public InternalAccountResponse byId(@PathVariable("accountId") String accountId) {
        return InternalAccountResponse.from(getAccount.execute(accountId));
    }
}
