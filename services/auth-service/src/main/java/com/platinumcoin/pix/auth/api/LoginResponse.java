package com.platinumcoin.pix.auth.api;

import com.platinumcoin.pix.auth.domain.IssuedToken;

/**
 * Login response, shaped to OpenAPI {@code /auth/login}: {@code accessToken}, a fixed
 * {@code tokenType} of "Bearer", and {@code expiresIn} in seconds. A thin wire DTO — it exists
 * because the wire shape (constant "Bearer", seconds) diverges from the domain {@link IssuedToken}.
 */
public record LoginResponse(String accessToken, String tokenType, long expiresIn) {

    static LoginResponse from(IssuedToken token) {
        return new LoginResponse(token.accessToken(), "Bearer", token.expiresInSeconds());
    }
}
