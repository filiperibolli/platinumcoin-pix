package com.platinumcoin.pix.payment.domain.port;

/**
 * Outbound port for reading an account's limit configuration from account-service — the owner of
 * {@code pix_accounts} (ADR-0006: services read each other's config over HTTP, never by sharing a
 * table). payment-service must know {@code dailyLimitCents} to size a reservation, and it must read
 * it <b>server-side</b> — the client never states its own limit.
 *
 * <p>The domain declares the shape; {@code infra/} implements it against
 * {@code GET /internal/accounts/{id}} (so no HTTP type reaches the use case, ADR-0010). Money stays
 * integer cents: the internal endpoint already exposes {@code dailyLimitCents} as a {@code long}, so
 * there is nothing to parse back from a decimal string.
 */
public interface AccountLimitClient {

    /**
     * The account's configured daily Pix limit, in integer cents.
     *
     * @throws AccountLookupException the account could not be read (not found, or account-service is
     *                                unreachable) — a send cannot proceed without a known limit
     */
    long dailyLimitCents(String accountId);
}
