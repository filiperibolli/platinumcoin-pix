package com.platinumcoin.pix.payment.api;

import com.platinumcoin.pix.common.security.AuthenticatedUser;
import com.platinumcoin.pix.payment.domain.usecase.GetStatementUseCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inbound adapter for {@code GET /v1/accounts/me/statement} — the paginated history behind the balance
 * (step 41), proxying ledger-service's internal statement seam (step 16) the same way
 * {@link AccountBalanceController} proxies its balance seam. Per ADR-0011 it does exactly three things:
 * take the account from the token, call one use case, map the result to the wire shape — the clamping
 * of {@code limit} and every log line about the business stage live in
 * {@link GetStatementUseCase}.
 *
 * <p><b>{@code /me}, and only {@code /me}</b>, exactly like the balance: the account is
 * {@link AuthenticatedUser}'s {@code accountId} claim, so there is no path or query parameter that
 * could name another account (Domain Safety Rule #1). {@code cursor} and {@code limit} are the only
 * query parameters, and {@code cursor} is opaque all the way through this controller — it is bound and
 * forwarded, never inspected.
 */
@RestController
@RequestMapping("/v1/accounts/me")
public class AccountStatementController {

    private final GetStatementUseCase getStatement;

    public AccountStatementController(GetStatementUseCase getStatement) {
        this.getStatement = getStatement;
    }

    @GetMapping("/statement")
    public StatementResponse statement(
            AuthenticatedUser user,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return StatementResponse.from(getStatement.execute(user.accountId(), cursor, limit));
    }
}
