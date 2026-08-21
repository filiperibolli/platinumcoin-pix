package com.platinumcoin.pix.payment.domain.exception;

/**
 * The ledger holds no BALANCE item for the account — mapped to {@code 404 BALANCE_NOT_FOUND} by
 * {@code PaymentExceptionHandler} (step 40).
 *
 * <p><b>Why not a zero balance.</b> "This account does not exist" and "this account has no money" are
 * different facts, and collapsing them would hand a customer a confident {@code R$ 0,00} for an
 * account that was never opened. The ledger already refuses to make that guess ({@code 404
 * LEDGER_ACCOUNT_NOT_FOUND}, step 13); this exception carries the same refusal across the seam.
 *
 * <p>Note the account here always comes from the caller's own JWT, so this is never an information
 * leak about someone else's account.
 */
public class BalanceNotFoundException extends RuntimeException {

    public BalanceNotFoundException(String message) {
        super(message);
    }
}
