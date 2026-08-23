package com.platinumcoin.pix.common.security;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platinumcoin.pix.common.error.ProblemDetailFactory;
import com.platinumcoin.pix.common.security.JwtAuthProperties.InternalRoute;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Validates {@code Authorization: Bearer <JWT>} on every request except the configured allow-list, and
 * decides <b>whether that particular token belongs on that particular route</b>. This is the one place
 * the platform decides who is acting — so it <b>fails closed</b> throughout.
 *
 * <h2>Two questions, not one (step 68, ADR-0017)</h2>
 * Until step 68 this filter asked only <i>is this token real?</i> — signature and expiry — and every
 * route that demanded a token accepted any token the platform had ever signed. That is authentication
 * standing in for authorization, and it made a user's login a working credential on
 * {@code POST /internal/ledger/postings}. So the filter now asks a second question, <i>may this caller
 * do this here?</i>, from the claims:
 *
 * <ul>
 *   <li><b>Public routes</b> ({@code /v1/**} and anything not internal) accept {@code typ=user} only.
 *       A service token there is {@code 403 PUBLIC_ROUTE_FORBIDDEN}.</li>
 *   <li><b>Internal ports</b> ({@code jwt.internal-path-patterns}, {@code /internal/**} by default)
 *       accept {@code typ=service} only, and additionally require {@code aud} = this service and
 *       {@code scope} = the scope declared for the route. Anything else is
 *       {@code 403 INTERNAL_PORT_FORBIDDEN}.</li>
 * </ul>
 *
 * The two surfaces are therefore <b>disjoint in both directions</b>. The reverse check is not
 * symmetry for its own sake: it means a service token that leaks — from a log, a heap dump, a crash
 * report — cannot be replayed against the public API to read or move a customer's money, so the
 * failure modes of the two token types never compose.
 *
 * <h2>401 vs 403</h2>
 * A missing, malformed, badly-signed or expired token is {@code 401 UNAUTHORIZED}: <i>we do not know
 * who you are</i>. A perfectly valid token of the wrong kind, audience or scope is {@code 403}:
 * <i>we know exactly who you are, and the answer is no.</i> Collapsing the two would tell a caller
 * holding a real token to go re-authenticate, which is both wrong and an invitation to retry.
 *
 * <p>Runs just after {@link com.platinumcoin.pix.common.web.CorrelationIdFilter} (which has already set
 * the correlationId on the MDC and the response), so every rejection body carries it. Because a servlet
 * filter runs <i>before</i> the DispatcherServlet, the {@code @RestControllerAdvice} error handler
 * cannot see rejections here — the filter writes the RFC 7807 body itself, reusing
 * {@link ProblemDetailFactory} so the contract matches every other error.
 */
@Order(JwtAuthFilter.ORDER)
public class JwtAuthFilter extends OncePerRequestFilter {

    /** Just after {@code CorrelationIdFilter} (highest precedence), before controllers. */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

    /** A user's token was presented to an internal port, or a service token that does not fit it. */
    public static final String INTERNAL_PORT_FORBIDDEN = "INTERNAL_PORT_FORBIDDEN";
    /** A service token was presented to a public, customer-facing route. */
    public static final String PUBLIC_ROUTE_FORBIDDEN = "PUBLIC_ROUTE_FORBIDDEN";

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String ACCOUNT_ID_CLAIM = "accountId";
    private static final String ANY_METHOD = "*";

    private final SecretKey key;
    private final List<String> publicPaths;
    private final List<String> internalPathPatterns;
    private final List<InternalRoute> internalRoutes;
    private final String serviceName;
    private final ObjectMapper objectMapper;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public JwtAuthFilter(JwtAuthProperties properties, ObjectMapper objectMapper) {
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(UTF_8));
        this.publicPaths = properties.publicPaths();
        this.internalPathPatterns = properties.internalPathPatterns();
        this.internalRoutes = properties.internalRoutes();
        this.serviceName = properties.serviceName();
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String path = pathOf(request);
        if (matchesAny(publicPaths, path)) {
            log.debug("Public path, skipping JWT validation | method={} path={}",
                    request.getMethod(), path);
            filterChain.doFilter(request, response);
            return;
        }

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (!StringUtils.hasText(header) || !header.startsWith(BEARER_PREFIX)) {
            rejectUnauthenticated(request, response, "missing_bearer_token");
            return;
        }

        String token = header.substring(BEARER_PREFIX.length()).trim();
        Claims claims;
        try {
            claims = parse(token);
        } catch (JwtException | IllegalArgumentException ex) {
            // Covers bad signature, expiry and malformed token. One 401 for all — never tell an
            // attacker which check failed. The token itself is never logged.
            rejectUnauthenticated(request, response, ex.getClass().getSimpleName());
            return;
        }

        if (matchesAny(internalPathPatterns, path)) {
            handleInternal(request, response, filterChain, claims, path);
        } else {
            handlePublic(request, response, filterChain, claims, path);
        }
    }

    /**
     * An internal port: service tokens only, addressed to this service, scoped to this route.
     *
     * <p>The three checks are ordered cheapest-first and each one is a separate {@code 403} with its
     * own log line, because "which of them said no" is the first question anyone debugging a broken
     * internal call asks — and the answer must not require decoding a token by hand.
     */
    private void handleInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain, Claims claims, String path) throws ServletException, IOException {
        String typ = typeOf(claims);
        if (!ServiceToken.TYP_SERVICE.equals(typ)) {
            // The finding this step closes, refused: a person's credential on a service's port.
            log.warn("A non-service token was presented to an internal port, returning 403 | typ={} "
                            + "sub={} accountId={} method={} path={} service={}",
                    typ, claims.getSubject(), claims.get(ACCOUNT_ID_CLAIM, String.class),
                    request.getMethod(), path, serviceName);
            rejectForbidden(response, INTERNAL_PORT_FORBIDDEN,
                    "Internal ports accept service credentials only.");
            return;
        }

        if (!isAddressedToThisService(claims)) {
            log.warn("A service token was not addressed to this service, returning 403 | iss={} aud={} "
                            + "expectedAud={} method={} path={}",
                    claims.getIssuer(), claims.getAudience(), serviceName, request.getMethod(), path);
            rejectForbidden(response, INTERNAL_PORT_FORBIDDEN,
                    "This service token is addressed to another service.");
            return;
        }

        Optional<String> required = requiredScope(request.getMethod(), path);
        if (required.isEmpty()) {
            // Fail closed. An internal route nobody declared a scope for is a configuration mistake,
            // and the safe reading of a mistake on a money path is "no".
            log.warn("An internal route has no declared scope, refusing rather than guessing, "
                            + "returning 403 | method={} path={} service={} declaredRoutes={}",
                    request.getMethod(), path, serviceName, internalRoutes.size());
            rejectForbidden(response, INTERNAL_PORT_FORBIDDEN,
                    "This internal route declares no scope.");
            return;
        }

        String presented = claims.get(ServiceToken.SCOPE_CLAIM, String.class);
        if (!required.get().equals(presented)) {
            log.warn("A service token was presented with the wrong scope for this route, returning "
                            + "403 | iss={} presentedScope={} requiredScope={} method={} path={}",
                    claims.getIssuer(), presented, required.get(), request.getMethod(), path);
            rejectForbidden(response, INTERNAL_PORT_FORBIDDEN,
                    "This service token is not scoped for this operation.");
            return;
        }

        var caller = new ServiceCaller(claims.getIssuer(), presented);
        log.debug("Service token accepted on an internal port | iss={} aud={} scope={} method={} "
                        + "path={} onBehalfOf={}",
                caller.serviceName(), serviceName, caller.scope(), request.getMethod(), path,
                request.getHeader(OnBehalfOf.HEADER));
        request.setAttribute(ServiceCaller.REQUEST_ATTRIBUTE, caller);
        filterChain.doFilter(request, response);
    }

    /** A customer-facing route: user tokens only, so a leaked service token is useless here. */
    private void handlePublic(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain, Claims claims, String path) throws ServletException, IOException {
        if (ServiceToken.TYP_SERVICE.equals(typeOf(claims))) {
            log.warn("A service token was presented to a public route, returning 403 | iss={} aud={} "
                            + "scope={} method={} path={}",
                    claims.getIssuer(), claims.getAudience(),
                    claims.get(ServiceToken.SCOPE_CLAIM, String.class), request.getMethod(), path);
            rejectForbidden(response, PUBLIC_ROUTE_FORBIDDEN,
                    "Service credentials are not accepted on customer-facing routes.");
            return;
        }

        AuthenticatedUser user;
        try {
            user = toPrincipal(claims);
        } catch (IllegalArgumentException ex) {
            // A well-signed token that omits identity is as untrustworthy as a forged one.
            rejectUnauthenticated(request, response, ex.getClass().getSimpleName());
            return;
        }

        // Who is acting, on what — at DEBUG, because it fires on every authenticated call. The
        // sandbox runs com.platinumcoin.pix at DEBUG (ADR-0012), so this is the "a call arrived"
        // line in practice, while a real deployment can turn it off without losing the WARN above.
        log.debug("JWT accepted, request is authenticated | userId={} accountId={} method={} path={}",
                user.userId(), user.accountId(), request.getMethod(), path);
        request.setAttribute(AuthenticatedUser.REQUEST_ATTRIBUTE, user);
        filterChain.doFilter(request, response);
    }

    /**
     * Whether {@code aud} names this service — <b>null-safe in both directions, and both nulls mean
     * no</b>.
     *
     * <p>Neither null is hypothetical. A token with {@code typ=service} and no {@code aud} at all is
     * what a hand-rolled minting script or a half-migrated issuer produces, and {@code Claims.getAudience()}
     * returns {@code null} for it rather than an empty set — which used to throw an NPE right here and
     * surface as a bare {@code 500} with no {@code code} and no {@code correlationId}, telling an
     * operator that <i>this service</i> was broken when the caller's credential was malformed.
     *
     * <p>The other null is a service that never set {@code jwt.service-name}. It has no identity, so
     * there is no audience any token could legitimately match — and a service that cannot say who it
     * is must not accept service traffic. Both cases fail closed, with the same ordinary {@code 403}.
     */
    private boolean isAddressedToThisService(Claims claims) {
        Set<String> audience = claims.getAudience();
        return StringUtils.hasText(serviceName) && audience != null && audience.contains(serviceName);
    }

    /** The scope declared for this method+path, first match wins, or empty if none is declared. */
    private Optional<String> requiredScope(String method, String path) {
        for (InternalRoute route : internalRoutes) {
            boolean methodMatches = !StringUtils.hasText(route.method())
                    || ANY_METHOD.equals(route.method())
                    || route.method().equalsIgnoreCase(method);
            if (methodMatches && pathMatcher.match(route.pattern(), path)) {
                return Optional.of(route.scope());
            }
        }
        return Optional.empty();
    }

    /** {@code typ}, defaulting to {@code user} — the safe reading of a token minted before step 68. */
    private static String typeOf(Claims claims) {
        String typ = claims.get(ServiceToken.TYP_CLAIM, String.class);
        return StringUtils.hasText(typ) ? typ : ServiceToken.TYP_USER;
    }

    private static String pathOf(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }

    private boolean matchesAny(List<String> patterns, String path) {
        for (String pattern : patterns) {
            if (pathMatcher.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    private Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private AuthenticatedUser toPrincipal(Claims claims) {
        String userId = claims.getSubject();
        String accountId = claims.get(ACCOUNT_ID_CLAIM, String.class);
        if (!StringUtils.hasText(userId) || !StringUtils.hasText(accountId)) {
            throw new IllegalArgumentException("token is missing subject or accountId claim");
        }
        return new AuthenticatedUser(userId, accountId);
    }

    private void rejectUnauthenticated(HttpServletRequest request, HttpServletResponse response,
            String reason) throws IOException {
        // The reason is logged (which check failed) but never returned — the 401 body stays generic
        // so the endpoint cannot be used as an oracle. method/path are here because they are what
        // makes a 401 debuggable now that no filter logs a per-request line.
        log.warn("JWT rejected, responding 401 UNAUTHORIZED | reason={} method={} path={}",
                reason, request.getMethod(), pathOf(request));
        write(response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required.");
    }

    /**
     * A valid token of the wrong kind. Unlike the 401, the {@code code} <b>is</b> returned: the caller
     * is an authenticated part of this platform (or something holding one of its tokens), the answer
     * will not change on retry, and an operator reading a 403 in a service log needs to know which of
     * the two surfaces refused it. The detail stays coarse — it never says which claim was wrong.
     */
    private void rejectForbidden(HttpServletResponse response, String code, String detail)
            throws IOException {
        write(response, HttpStatus.FORBIDDEN, code, detail);
    }

    private void write(HttpServletResponse response, HttpStatus status, String code, String detail)
            throws IOException {
        ProblemDetail problem = ProblemDetailFactory.of(status, code, detail);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), problem);
    }
}
