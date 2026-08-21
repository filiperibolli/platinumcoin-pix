package com.platinumcoin.pix.payment.api;

import com.platinumcoin.pix.common.security.AuthenticatedUser;
import com.platinumcoin.pix.payment.domain.usecase.GetBalanceUseCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inbound adapter for {@code GET /v1/accounts/me/balance} — the platform's highest-volume read, served
 * from Redis with a ledger fallback (step 40, ADR-0008). Per ADR-0011 it does exactly three things:
 * take the account from the token, call one use case, map the result to the wire shape. No cache
 * lookup, no TTL, no clock lives here.
 *
 * <p><b>{@code /me}, and only {@code /me}.</b> The account is {@link AuthenticatedUser}'s
 * {@code accountId} claim; there is no path variable and no query parameter that could name another
 * account, so "read someone else's balance" is not a request this endpoint can express — the same
 * mechanism that protects the debited account in the send flow (Domain Safety Rule #1). Reading a
 * <i>counterparty's</i> balance is an internal seam on ledger-service, not a public surface.
 *
 * <p><b>Why payment-service and not account-service.</b> account-service owns who you are and what your
 * limits are; the balance is a projection of the ledger, and payment-service is the service that
 * already holds the ledger seam (and the cache in front of it). Splitting them would mean two services
 * with a Redis client and two places for the cache-aside policy to drift.
 */
@RestController
@RequestMapping("/v1/accounts/me")
public class AccountBalanceController {

    private final GetBalanceUseCase getBalance;

    public AccountBalanceController(GetBalanceUseCase getBalance) {
        this.getBalance = getBalance;
    }

    @GetMapping("/balance")
    public BalanceResponse balance(AuthenticatedUser user) {
        return BalanceResponse.from(getBalance.execute(user.accountId()));
    }
}
