package com.platinumcoin.pix.ledger.domain.usecase;

import com.platinumcoin.pix.ledger.domain.exception.LedgerAccountNotFoundException;
import com.platinumcoin.pix.ledger.domain.model.Balance;
import com.platinumcoin.pix.ledger.domain.port.LedgerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Read the balance of a ledger account (ADR-0011: one use case per inbound operation, so
 * {@code ls domain/usecase/} is this service's capability list — one capability today, the posting
 * and the statement joining it in steps 14 and 16).
 *
 * <p>Deliberately <b>not</b> account-scoped: the account comes from the caller, not from a token.
 * This is an internal seam (ADR-0006) — payment-service reads a payee's balance, reconciliation reads
 * {@code SPI_CLEARING} — and the platform-level rule that "the debited account comes from the JWT"
 * binds the <i>money-moving</i> endpoint in payment-service, which is where a client can actually
 * name an account. Nothing here moves money.
 */
public class GetBalanceUseCase {

    private static final Logger log = LoggerFactory.getLogger(GetBalanceUseCase.class);

    private final LedgerRepository ledger;

    public GetBalanceUseCase(LedgerRepository ledger) {
        this.ledger = ledger;
    }

    public Balance execute(String accountId) {
        log.info("Balance read requested for a ledger account | accountId={}", accountId);
        Balance balance = ledger.getBalance(accountId)
                .orElseThrow(() -> {
                    // An ordinary lookup miss, not a failure of this service — INFO keeps the trace
                    // of the call complete without pretending something broke.
                    log.info("No BALANCE item exists for this account, returning 404 | accountId={}",
                            accountId);
                    return new LedgerAccountNotFoundException(
                            "No ledger account found for id " + accountId + ".");
                });
        log.info("Balance read resolved from the ledger | accountId={} balanceCents={} version={}",
                balance.accountId(), balance.balanceCents(), balance.version());
        return balance;
    }
}
