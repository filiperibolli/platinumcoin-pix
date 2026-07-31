package com.platinumcoin.pix.account.api;

import com.platinumcoin.pix.account.domain.PixKey;
import java.time.Instant;

/**
 * Public view of a Pix key (matches the OpenAPI {@code PixKey} schema). Deliberately exposes only
 * {@code keyType / keyValue / createdAt} — <b>not</b> the owning {@code accountId}. The key value is
 * itself a globally resolvable identifier, but the internal account id behind it is not something a
 * caller ever needs from this endpoint, so it never leaves the service.
 */
public record PixKeyResponse(String keyType, String keyValue, Instant createdAt) {

    static PixKeyResponse from(PixKey key) {
        return new PixKeyResponse(key.keyType().name(), key.keyValue(), key.createdAt());
    }
}
