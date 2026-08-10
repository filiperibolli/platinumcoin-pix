package com.platinumcoin.pix.account.domain.port;

import com.platinumcoin.pix.account.domain.model.PixKey;
import java.util.List;
import java.util.Optional;

/**
 * Outbound port for the {@code pix_keys} table (ADR-0010: the domain declares the interface,
 * {@code infra/} implements it against DynamoDB). Three access patterns from docs/data-model.md §2,
 * plus the write that enforces the model's critical invariant — <b>global uniqueness</b>.
 *
 * <p>The uniqueness enforcement is expressed here as a boolean, not an exception: {@link #register}
 * returns {@code false} when the key value is already taken. That keeps the DynamoDB-specific
 * {@code ConditionalCheckFailedException} inside {@code infra/} — the domain/api never imports an AWS
 * type — while still exposing the one bit of information the caller needs to answer {@code 409}.
 */
public interface PixKeyRepository {

    /**
     * Atomically claim {@code key.keyValue} as globally unique via a conditional {@code PutItem}
     * ({@code attribute_not_exists(pk)}). Returns {@code true} if this call won the claim, or
     * {@code false} if the value was already registered (by any account) — the check and the write
     * are one operation, so no read-then-write race is possible.
     */
    boolean register(PixKey key);

    /** All keys owned by {@code accountId} (GSI1 query on {@code ACCOUNT#<accountId>}). */
    List<PixKey> listByAccount(String accountId);

    /** The key registered under {@code keyValue}, or empty if none exists. */
    Optional<PixKey> findByValue(String keyValue);

    /** Delete the key under {@code keyValue}; a no-op if it is already absent (idempotent). */
    void delete(String keyValue);
}
