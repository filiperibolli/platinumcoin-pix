package com.platinumcoin.pix.auth.domain.port;

import com.platinumcoin.pix.auth.domain.model.IssuedToken;

/**
 * Outbound port for minting an access token from an authenticated identity. The HS256/jjwt
 * implementation lives in {@code infra/} ({@code JwtIssuer}); the domain only knows it can turn a
 * {@code (userId, accountId)} pair into an {@link IssuedToken}.
 */
public interface TokenIssuer {

    IssuedToken issue(String userId, String accountId);
}
