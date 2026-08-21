package com.platinumcoin.pix.payment.domain.model;

import java.time.Instant;

/**
 * A balance as this service serves it: integer cents, plus <b>when it was true</b> (step 40).
 *
 * <p>{@code asOf} is not decoration. This value may come from Redis and be up to one TTL old
 * (ADR-0008), so the age of the number is part of the number — a client that must reason about
 * freshness (or a support engineer looking at a screenshot) can subtract it. It is stamped once, by
 * {@link com.platinumcoin.pix.payment.domain.usecase.GetBalanceUseCase} at the moment the ledger was
 * read, and then travels with the value into the cache and back out of it; a cache hit carries the
 * original instant, never the instant of the hit.
 *
 * <p>Money is integer cents here as everywhere internally — the decimal string is produced at the
 * {@code api/} edge and nowhere else (CLAUDE.md, Domain Safety Rule #6).
 *
 * @param accountId    the ledger account this balance belongs to
 * @param balanceCents the amount, integer cents (may be negative for system accounts)
 * @param asOf         when the ledger was read for this value
 */
public record AccountBalance(String accountId, long balanceCents, Instant asOf) {
}
