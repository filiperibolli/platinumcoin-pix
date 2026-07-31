package com.platinumcoin.pix.account.domain;

import java.util.Optional;

/**
 * Outbound port for reading accounts (ADR-0010: the domain declares the interface, {@code infra/}
 * implements it against DynamoDB). Two access patterns, both from docs/data-model.md §1:
 *
 * <ul>
 *   <li>{@link #findByUser(String, String)} — the {@code GET /v1/accounts/me} path. The caller's
 *       {@code userId} + {@code accountId} both come from the validated JWT, so this is a direct,
 *       strongly-consistent {@code GetItem} on the base-table key ({@code USER#..} / {@code ACCOUNT#..}).</li>
 *   <li>{@link #findByAccountId(String)} — the internal lookup by id, served via GSI1
 *       ({@code ACCOUNT#<accountId>}) for services that only know the account id.</li>
 * </ul>
 *
 * <p>Both return {@link Optional}: "unknown account" is an ordinary empty result here, and the
 * {@code api/} layer decides that empty maps to a {@code 404 ACCOUNT_NOT_FOUND}. Keeping the port
 * free of HTTP concerns is what lets the domain stay framework-free.
 */
public interface AccountRepository {

    /** The account owned by {@code userId} under {@code accountId}, or empty if none exists. */
    Optional<Account> findByUser(String userId, String accountId);

    /** The account with {@code accountId} (GSI1 lookup), or empty if none exists. */
    Optional<Account> findByAccountId(String accountId);
}
