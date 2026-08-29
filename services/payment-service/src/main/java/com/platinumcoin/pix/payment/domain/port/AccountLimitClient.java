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

    /**
     * When the account was opened (step 53).
     *
     * <p>Used by the cold-statement export to refuse a range reaching back before the account existed —
     * months that can only ever be empty, so exporting them would hand a customer a file with a
     * misleading gap in it instead of an answer.
     *
     * <p><b>On the name of this port.</b> It reads more than a limit now, and it is deliberately not
     * renamed: it is referenced from thirty-odd places across production code, fakes and stubs, and a
     * rename touching all of them inside a step about exports would bury the change that matters. Read
     * it as "the account facts payment-service reads server-side" — both of them share one adapter and
     * one endpoint ({@code GET /internal/accounts/{id}}), and neither is ever accepted from the client.
     *
     * @throws AccountLookupException the account could not be read (not found, or account-service is
     *                                unreachable)
     */
    java.time.Instant openedAt(String accountId);
}
