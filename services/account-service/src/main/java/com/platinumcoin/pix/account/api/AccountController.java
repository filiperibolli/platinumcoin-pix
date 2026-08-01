package com.platinumcoin.pix.account.api;

import com.platinumcoin.pix.account.domain.usecase.GetMyAccountUseCase;
import com.platinumcoin.pix.common.security.AuthenticatedUser;
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
 * <p>Per ADR-0011 the controller holds no policy and no port: it calls exactly one use case and maps
 * the result to the wire record. "No such account" is raised by the use case as a domain exception
 * and turned into {@code 404 ACCOUNT_NOT_FOUND} by {@link AccountExceptionHandler}.
 */
@RestController
@RequestMapping("/v1/accounts")
public class AccountController {

    private final GetMyAccountUseCase getMyAccount;

    public AccountController(GetMyAccountUseCase getMyAccount) {
        this.getMyAccount = getMyAccount;
    }

    @GetMapping("/me")
    public AccountResponse me(AuthenticatedUser user) {
        return AccountResponse.from(getMyAccount.execute(user.userId(), user.accountId()));
    }
}
