package com.platinumcoin.pix.fraud.domain.usecase;

import java.time.Instant;

/**
 * The input to {@link ScoreFraudUseCase}: who is paying, whom, how much, and when. Mirrors the step's
 * {@code ScoreRequest} shape but lives in the domain as a plain record; the {@code api/} layer binds the
 * wire body ({@code ScoreRequest}) and hands this in.
 *
 * <p>{@code accountId} arrives as <i>data</i> here, not from a JWT — this is an internal service-to-service
 * seam, and the caller (payment-service) already took the account from its own token before forwarding
 * it (Domain Safety Rule #1 is enforced upstream, at the money-moving endpoint). {@code amountCents} is
 * integer cents. {@code timestamp} is the transfer time used for the odd-hours rule; it may be
 * {@code null}, in which case the use case falls back to its injected {@link java.time.Clock}.
 */
public record ScoreCommand(String accountId, String pixKey, long amountCents, Instant timestamp) {
}
