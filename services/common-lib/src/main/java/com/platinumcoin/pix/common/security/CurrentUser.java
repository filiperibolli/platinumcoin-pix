package com.platinumcoin.pix.common.security;

import java.util.Optional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Static accessor to the caller established by {@link JwtAuthFilter}, for code paths that are not a
 * controller method parameter (services, filters, event enrichment) and so cannot use
 * {@link AuthenticatedUserArgumentResolver}. Reads the request-scoped principal via
 * {@link RequestContextHolder} — same source, no ThreadLocal of our own to leak.
 *
 * <p>{@link #require()} is the money-path form: it throws rather than return an unauthenticated
 * caller, so a flow that must know who is acting fails loudly instead of debiting the wrong account.
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    /** The authenticated caller if one is bound to the current request, otherwise empty. */
    public static Optional<AuthenticatedUser> current() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return Optional.empty();
        }
        Object principal = attributes.getAttribute(
                AuthenticatedUser.REQUEST_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        return Optional.ofNullable((AuthenticatedUser) principal);
    }

    /** The authenticated caller, or an {@link IllegalStateException} if the request has none. */
    public static AuthenticatedUser require() {
        return current().orElseThrow(
                () -> new IllegalStateException("no authenticated user bound to the current request"));
    }
}
