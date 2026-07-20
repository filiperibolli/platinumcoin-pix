package com.platinumcoin.pix.common.security;

/**
 * The authenticated caller, derived <b>only</b> from a validated JWT: {@code userId} is the token
 * subject, {@code accountId} the {@code accountId} claim. This is the single source of "who is
 * acting" — controllers inject it (see {@link AuthenticatedUserArgumentResolver}) and money-moving
 * flows read the debited account from {@link #accountId()}, never from the request body
 * (Domain Safety Rule #1). Making the source account come from here, and only here, is what makes
 * "never from the payload" the <i>only</i> expressible path.
 */
public record AuthenticatedUser(String userId, String accountId) {

    /** Request-scope attribute under which {@link JwtAuthFilter} stashes the resolved principal. */
    public static final String REQUEST_ATTRIBUTE = AuthenticatedUser.class.getName();
}
