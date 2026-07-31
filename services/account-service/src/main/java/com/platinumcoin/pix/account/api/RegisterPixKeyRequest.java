package com.platinumcoin.pix.account.api;

import com.platinumcoin.pix.account.domain.PixKeyType;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for {@code POST /v1/pix-keys}. Only two structural things are validated here (the wire
 * shape): {@code keyType} must be one of the four enum constants (an unknown value fails Jackson
 * binding → {@code 400}) and must be present. The <b>value's</b> per-type validity (email shape, CPF
 * digits, …) is a domain concern handled in the controller via {@link PixKeyType}, mapping to a
 * {@code 422 INVALID_PIX_KEY} — a well-formed request whose value is semantically wrong.
 *
 * <p>Note what is <b>absent</b>: no account/user field. Ownership always comes from the JWT (Domain
 * Safety Rule #1), never the body. For an {@link PixKeyType#EVP} key {@code keyValue} is ignored
 * (the server mints a UUID), so it is optional.
 */
public record RegisterPixKeyRequest(
        @NotNull PixKeyType keyType,
        String keyValue) {
}
