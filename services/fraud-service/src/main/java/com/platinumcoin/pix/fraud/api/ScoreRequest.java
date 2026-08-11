package com.platinumcoin.pix.fraud.api;

import com.platinumcoin.pix.fraud.domain.usecase.ScoreCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.time.Instant;

/**
 * Request body for {@code POST /internal/fraud/score}. Only the wire shape is validated here (a
 * malformed body is a {@code 400 VALIDATION_ERROR}, handled by common-lib's {@code
 * GlobalExceptionHandler}); the scoring policy lives entirely in {@link ScoreCommand}'s use case.
 *
 * <ul>
 *   <li>{@code accountId} / {@code pixKey} — {@link NotBlank}; the payer and the payee key.</li>
 *   <li>{@code amountCents} — integer cents, {@link Positive} (a zero/negative amount is not scorable).</li>
 *   <li>{@code timestamp} — the transfer time for the odd-hours rule; <b>optional</b>: when absent the
 *       use case falls back to its injected clock (so this endpoint is usable ad-hoc from the API
 *       explorer without hand-crafting a timestamp).</li>
 * </ul>
 *
 * <p>Unlike the money-moving endpoints, {@code accountId} is a body field, not a JWT claim: this is an
 * internal service-to-service call and payment-service — which <i>did</i> take the account from its own
 * token (Domain Safety Rule #1) — forwards it here as data to be scored (step 25).
 */
public record ScoreRequest(
        @NotBlank String accountId,
        @NotBlank String pixKey,
        @Positive long amountCents,
        Instant timestamp) {

    ScoreCommand toCommand() {
        return new ScoreCommand(accountId, pixKey, amountCents, timestamp);
    }
}
