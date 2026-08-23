package com.platinumcoin.pix.common.security;

/**
 * The claim names that carry workload identity, and the two token types (ADR-0017). Shared by the
 * issuer that writes them ({@link ServiceTokenIssuer}), the filter that reads them
 * ({@link JwtAuthFilter}) and auth-service, which stamps {@link #TYP_USER} on every login token.
 *
 * <p><b>A token with no {@code typ} is read as a user token.</b> That is the safe default and not an
 * arbitrary one: before this step every token in the platform was a user token, so the absent claim
 * has exactly one possible meaning — and reading it the other way would let an unstamped token reach
 * an internal port, which is the failure this whole step exists to remove.
 */
public final class ServiceToken {

    /** Claim distinguishing a person's token from a workload's. */
    public static final String TYP_CLAIM = "typ";
    /** The operation a service token was minted for — one of {@link InternalApi}'s scopes. */
    public static final String SCOPE_CLAIM = "scope";

    /** A token auth-service issued to a human on login. */
    public static final String TYP_USER = "user";
    /** A token a service minted for one call to one other service. */
    public static final String TYP_SERVICE = "service";

    private ServiceToken() {
    }
}
