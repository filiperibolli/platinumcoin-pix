package com.platinumcoin.pix.ledger.api;

import com.platinumcoin.pix.ledger.domain.usecase.GetBalanceUseCase;
import com.platinumcoin.pix.ledger.domain.usecase.GetStatementUseCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
 * <p>Per ADR-0011 this class does exactly three things: bind the inputs, call one use case, map the
 * result — the not-found decision, the limit policy, the cursor decoding/validation and the logging
 * of the business stage all live behind {@link GetBalanceUseCase} / {@link GetStatementUseCase}, and
 * {@link LedgerExceptionHandler} owns the HTTP mapping.
 */
@RestController
@RequestMapping("/internal/ledger/accounts")
public class InternalLedgerController {

    private final GetBalanceUseCase getBalance;
    private final GetStatementUseCase getStatement;

    public InternalLedgerController(GetBalanceUseCase getBalance, GetStatementUseCase getStatement) {
        this.getBalance = getBalance;
        this.getStatement = getStatement;
    }

    @GetMapping("/{accountId}/balance")
    public BalanceResponse balance(@PathVariable("accountId") String accountId) {
        return BalanceResponse.from(getBalance.execute(accountId));
    }

    /**
     * One page of the account's statement, newest first. {@code cursor} is the opaque token from a
     * previous page (absent on the first); {@code limit} is the client's requested page size (absent
     * ⇒ the use case's default). The controller does no clamping and no cursor parsing — both are
     * policy the use case and the adapter own.
     */
    @GetMapping("/{accountId}/entries")
    public StatementResponse entries(
            @PathVariable("accountId") String accountId,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return StatementResponse.from(getStatement.execute(accountId, cursor, limit));
    }
}
