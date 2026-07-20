package com.platinumcoin.pix.auth.api;

import com.platinumcoin.pix.common.security.AuthenticatedUser;

/**
 * Echoes the identity the platform derived from the bearer token. Its whole point is to prove the
 * common-lib {@link com.platinumcoin.pix.common.security.JwtAuthFilter} is wired and turning the
 * {@code accountId} claim into a first-class principal — later services read that same principal to
 * decide the debited account (Domain Safety Rule #1).
 */
public record MeResponse(String userId, String accountId) {

    static MeResponse from(AuthenticatedUser user) {
        return new MeResponse(user.userId(), user.accountId());
    }
}
