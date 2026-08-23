package com.platinumcoin.pix.common.security;

/**
 * The authenticated <b>workload</b> behind an {@code /internal/**} call, derived only from a validated
 * service token: {@code serviceName} is the token's {@code iss}, {@code scope} the operation it was
 * minted for (ADR-0017).
 *
 * <p>The counterpart of {@link AuthenticatedUser}, and deliberately a different type rather than a
 * user with a service-shaped name. A service token has no {@code accountId} and asserts no authority
 * to spend anyone's money; letting it flow through the same principal would make "is this a person?" a
 * question answered by reading a string, which is how a confused deputy is built in the first place.
 *
 * <p>Nothing on the money path reads this today — the internal endpoints derive their behaviour from
 * the request body, as they must (they are told both legs). It is stashed so logs and, later, the audit
 * trail can name the acting service without re-parsing the token.
 */
public record ServiceCaller(String serviceName, String scope) {

    /** Request-scope attribute under which {@link JwtAuthFilter} stashes the resolved caller. */
    public static final String REQUEST_ATTRIBUTE = ServiceCaller.class.getName();
}
