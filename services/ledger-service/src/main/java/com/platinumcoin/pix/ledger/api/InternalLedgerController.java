package com.platinumcoin.pix.ledger.api;

import com.platinumcoin.pix.ledger.domain.usecase.GetBalanceUseCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inbound adapter for {@code GET /internal/ledger/accounts/{accountId}/balance} — how other services
 * read a balance without touching {@code pix_ledger} themselves (ADR-0006: the ledger is the only
 * writer, and the only reader anyone should have to trust).
 *
 * <p>The path is {@code /internal/**} and therefore <b>not</b> on {@code jwt.public-paths}: the call
 * still requires a valid token (the shared {@code JwtAuthFilter} rejects it otherwise), but unlike a
 * {@code /me} endpoint the account comes from the path, because the caller is asking about someone
 * else's balance on their behalf. A deployed posture would gate this seam with a service credential
 * or mTLS instead of an end-user token (step-45 hardening).
 *
 * <p>Per ADR-0011 this class does exactly three things: bind the path variable, call one use case,
 * map the result — the not-found decision, the logging of the business stage and the port are all
 * behind {@link GetBalanceUseCase}, and {@link LedgerExceptionHandler} owns the HTTP mapping.
 */
@RestController
@RequestMapping("/internal/ledger/accounts")
public class InternalLedgerController {

    private final GetBalanceUseCase getBalance;

    public InternalLedgerController(GetBalanceUseCase getBalance) {
        this.getBalance = getBalance;
    }

    @GetMapping("/{accountId}/balance")
    public BalanceResponse balance(@PathVariable("accountId") String accountId) {
        return BalanceResponse.from(getBalance.execute(accountId));
    }
}
