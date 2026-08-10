package com.platinumcoin.pix.account.domain.model;

import java.time.Instant;

/**
 * A customer account as owned by account-service (table {@code pix_accounts}, docs/data-model.md §1).
 * Plain Java — no framework or AWS types — so it obeys the ADR-0010 dependency rule (enforced by
 * {@code AccountArchitectureTest}).
 *
 * <p>{@code dailyLimitCents} is money and therefore <b>integer cents</b> ({@code long}), never a
 * {@code double}: the value stays in cents through the whole domain and is only formatted to a
 * decimal string at the {@code api/} edge (and even then only on the public view — the internal
 * service-to-service view keeps cents, because its consumers do integer arithmetic on the limit).
 *
 * @param status raw stored status (e.g. {@code ACTIVE}); kept as a string here so the domain never
 *               fails to load an account just because a new status value was added to the table.
 */
public record Account(
        String accountId,
        String userId,
        String status,
        long dailyLimitCents,
        Instant createdAt) {
}
