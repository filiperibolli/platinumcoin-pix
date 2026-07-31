package com.platinumcoin.pix.account.domain;

import java.time.Instant;

/**
 * A registered Pix key as owned by account-service (table {@code pix_keys}, docs/data-model.md §2).
 * Plain Java — no framework or AWS types — per the ADR-0010 dependency rule (enforced by
 * {@code AccountArchitectureTest}).
 *
 * <p>{@code keyValue} is already <b>normalized</b> ({@link PixKeyType#normalize}) — it is the exact
 * string used as the global-uniqueness key {@code KEY#<keyValue>}, so an account's own view and the
 * uniqueness check never disagree on casing. Both {@code accountId} and {@code userId} come from the
 * caller's validated JWT, never from the request body.
 */
public record PixKey(
        PixKeyType keyType,
        String keyValue,
        String accountId,
        String userId,
        Instant createdAt) {
}
