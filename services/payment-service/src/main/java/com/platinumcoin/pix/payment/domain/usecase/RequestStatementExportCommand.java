package com.platinumcoin.pix.payment.domain.usecase;

/**
 * What a client asks for when it requests a cold-statement export (step 53).
 *
 * <p><b>The account is not a client input.</b> It is here because the controller put the JWT's
 * {@code accountId} claim into it, exactly as {@link SendPixCommand} does for a send (Domain Safety
 * Rule #1). The wire shape ({@code StatementExportRequest}) has no account field at all, so there is
 * nothing for a client to send and nothing for this platform to have to ignore.
 *
 * @param accountId      the caller's own account, from the token
 * @param idempotencyKey the {@code Idempotency-Key} header, verbatim — the use case decides whether it
 *                       is acceptable, because a missing key is a business refusal and not a binding
 *                       failure
 * @param fromMonth      first month, inclusive, as sent ({@code yyyy-MM})
 * @param toMonth        last month, inclusive, as sent
 */
public record RequestStatementExportCommand(
        String accountId, String idempotencyKey, String fromMonth, String toMonth) {
}
